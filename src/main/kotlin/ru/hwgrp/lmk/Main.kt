package ru.hwgrp.lmk

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.system.exitProcess

private const val HELP = """LMK tool (Java 21+)
read  --config FILE --hsm-id ID --lmk-id ID --pair-id NUMBER
write --config FILE --hsm-id ID --lmk-id ID --pair-id NUMBER --left HEX --right HEX
HEX: exactly 8 bytes (16 hex digits); spaces between bytes are allowed.
Existing HSM, LMK and pair IDs are required. IDs are matched exactly.
Run without arguments for interactive mode. Enter one command per line.
Interactive commands: help, exit (or quit). Quote values containing spaces.
"""
private val json = Json { prettyPrint = true }

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        runInteractive(::readlnOrNull, ::println, System.err::println) {
            print("lmk> ")
            System.out.flush()
        }
    } else {
        exitProcess(runCli(args, ::println, System.err::println))
    }
}

fun runCli(args: Array<String>, out: (String) -> Unit, err: (String) -> Unit): Int = try {
    if (args.contentEquals(arrayOf("--help")) || args.contentEquals(arrayOf("-h"))) {
        out(HELP)
    } else {
        val command = args.firstOrNull()
        require(command == "read" || command == "write") { HELP }
        val required = setOf("--config", "--hsm-id", "--lmk-id", "--pair-id") +
            if (command == "write") setOf("--left", "--right") else emptySet()
        val options = mutableMapOf<String, String>()
        require((args.size - 1) % 2 == 0) { "Each option requires a value.\n$HELP" }
        for (i in 1 until args.size step 2) {
            require(args[i] in required) { "Unknown option: ${args[i]}" }
            require(options.put(args[i], args[i + 1]) == null) { "Duplicate option: ${args[i]}" }
        }
        require(options.keys == required) { "Required options: ${required.joinToString(" ")}" }
        val file = Path.of(options.getValue("--config")).toRealPath()
        val original = Files.readString(file)
        val root = json.parseToJsonElement(original).jsonObject
        val configs = root.getValue("hsmConfigs").jsonArray
        val matches = configs.indices.filter { configs[it].jsonObject["id"]?.jsonPrimitive?.content == options["--hsm-id"] }
        require(matches.size == 1) { "HSM ID must match exactly one configuration (found ${matches.size})." }
        val index = matches.single()
        val hsm = configs[index].jsonObject
        val config = hsm.getValue("hsmConfig").jsonObject
        val storage = config.getValue("lmkStorage").jsonObject
        val lmks = storage.getValue("liveLmks").jsonObject
        val lmkId = options.getValue("--lmk-id")
        val lmk = lmks[lmkId]?.jsonObject ?: error("LMK ID not found: $lmkId")
        val pairs = lmk.getValue("pairs").jsonObject
        val pairId = options.getValue("--pair-id")
        val pair = pairs[pairId]?.jsonObject ?: error("Pair not found: $pairId")
        if (command == "read") {
            val left = toHex(pair.getValue("leftKey"))
            val right = toHex(pair.getValue("rightKey"))
            out("leftKey:  $left")
            out("rightKey: $right")
        } else {
            val left = fromHex(options.getValue("--left"))
            val right = fromHex(options.getValue("--right"))
            val updatedPair = pair.with("leftKey", left).with("rightKey", right)
            val updatedHsm = hsm.with("hsmConfig", config.with("lmkStorage", storage.with("liveLmks",
                lmks.with(lmkId, lmk.with("pairs", pairs.with(pairId, updatedPair))))))
            val updated = root.with("hsmConfigs", JsonArray(configs.mapIndexed { i, value -> if (i == index) updatedHsm else value }))
            val serialized = json.encodeToString(JsonElement.serializer(), updated) + "\n"
            val temp = Files.createTempFile(file.parent, ".lmk-", ".tmp")
            try {
                Files.writeString(temp, serialized)
                if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
                    Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(file))
                }
                require(Files.readString(file) == original) { "Configuration changed during update; retry." }
                // Fail rather than fall back to a potentially partial overwrite.
                Files.move(temp, file, ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temp) }
            out("Updated HSM ${options["--hsm-id"]}, LMK $lmkId, pair $pairId.")
        }
    }
    0
} catch (e: Exception) {
    err("Error: ${e.message ?: e.javaClass.simpleName}")
    1
}

private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))

private fun fromHex(value: String): JsonArray {
    require(Regex("(?:[0-9a-fA-F]{2}\\s*){8}").matches(value.trim())) {
        "Each key must contain exactly 8 HEX bytes (16 hex digits)."
    }
    return JsonArray(value.filterNot(Char::isWhitespace).chunked(2).map {
        JsonPrimitive(it.toInt(16).toByte().toInt())
    })
}

private fun toHex(value: JsonElement): String {
    val bytes = value.jsonArray
    require(bytes.size == 8) { "Stored key must contain exactly 8 bytes." }
    return bytes.joinToString("") {
        val p = it.jsonPrimitive
        val number = p.intOrNull
        require(!p.isString && number != null && number in -128..127) { "Stored byte must be an integer in -128..127." }
        (number and 255).toString(16).padStart(2, '0').uppercase()
    }
}

fun runInteractive(
    input: () -> String?,
    out: (String) -> Unit,
    err: (String) -> Unit,
    prompt: () -> Unit
) {
    out("LMK tool. Enter read or write with options; help for usage, exit to quit.")
    while (true) {
        prompt()
        val line = input() ?: return
        val args = try {
            splitCommand(line)
        } catch (e: IllegalArgumentException) {
            err("Error: ${e.message}")
            continue
        }
        if (args.isEmpty()) continue
        when {
            args.size == 1 && args[0] in setOf("exit", "quit") -> return
            args.size == 1 && args[0] in setOf("help", "--help", "-h") -> out(HELP)
            else -> runCli(args.toTypedArray(), out, err)
        }
    }
}

// Quotes group values; backslashes are literal so Windows paths stay intact.
// This is a command tokenizer, not a shell: no expansion or command execution.
private fun splitCommand(line: String): List<String> {
    val result = mutableListOf<String>()
    val token = StringBuilder()
    var quote: Char? = null
    var started = false
    for (char in line) {
        when {
            quote != null -> if (char == quote) quote = null else token.append(char)
            char == '\'' || char == '"' -> { quote = char; started = true }
            char.isWhitespace() -> if (started) {
                result.add(token.toString())
                token.setLength(0)
                started = false
            }
            else -> { token.append(char); started = true }
        }
    }
    require(quote == null) { "Unclosed quote. Enter the entire command on a single line." }
    if (started) result.add(token.toString())
    return result
}
