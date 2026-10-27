package ru.hwgrp.lmk

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.system.exitProcess

private const val HELP = """LMK tool (Java 21+)
read  --config FILE [--hsm-id ID] --lmk-id ID --pair-id NUMBER
write --config FILE [--hsm-id ID] --lmk-id ID --pair-id NUMBER --left HEX --right HEX
Full configuration (hsmConfigs): --hsm-id is required.
Standalone storage (liveLmks at root): --hsm-id is not allowed.
Existing IDs are required and matched exactly, including leading zeros.
HEX: exactly 8 bytes (16 hex digits); spaces between bytes are allowed.
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
        val required = setOf("--config", "--lmk-id", "--pair-id") +
            if (command == "write") setOf("--left", "--right") else emptySet()
        val allowed = required + "--hsm-id"
        val options = mutableMapOf<String, String>()
        require((args.size - 1) % 2 == 0) { "Each option requires a value.\n$HELP" }
        for (i in 1 until args.size step 2) {
            require(args[i] in allowed) { "Unknown option: ${args[i]}" }
            require(options.put(args[i], args[i + 1]) == null) { "Duplicate option: ${args[i]}" }
        }
        require(options.keys.containsAll(required)) { "Required options: ${required.joinToString(" ")}" }
        val file = Path.of(options.getValue("--config")).toRealPath()
        val original = Files.readString(file)
        val root = json.parseToJsonElement(original)
        val tokens = selectionPath(root, options)
        val target = resolveAt(root, tokens)
        require(target is JsonObject && "leftKey" in target && "rightKey" in target) {
            "JSON path must identify an object containing leftKey and rightKey: ${pointerText(tokens)}"
        }
        val pair = target
        if (command == "read") {
            val left = toHex(pair.getValue("leftKey"))
            val right = toHex(pair.getValue("rightKey"))
            out("leftKey:  $left")
            out("rightKey: $right")
        } else {
            val left = fromHex(options.getValue("--left"))
            val right = fromHex(options.getValue("--right"))
            val updatedPair = pair.with("leftKey", left).with("rightKey", right)
            val updated = replaceAt(root, tokens, updatedPair)
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
            out("Updated " + (options["--hsm-id"]?.let { "HSM $it, " } ?: "") +
                "LMK ${options["--lmk-id"]}, pair ${options["--pair-id"]}.")
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

private fun pointerText(tokens: List<String>): String =
    tokens.joinToString("") { "/" + it.replace("~", "~0").replace("/", "~1") }

private fun arrayIndex(token: String, size: Int, path: String): Int {
    require(Regex("0|[1-9][0-9]*").matches(token)) { "Invalid array index at JSON path: $path" }
    val index = token.toIntOrNull()
    require(index != null && index < size) { "Array index out of bounds at JSON path: $path" }
    return index
}

private fun resolveAt(root: JsonElement, tokens: List<String>): JsonElement {
    var current = root
    for ((i, token) in tokens.withIndex()) {
        val path = pointerText(tokens.take(i + 1))
        current = when (val node = current) {
            is JsonObject -> node[token] ?: error("JSON path not found: $path")
            is JsonArray -> node[arrayIndex(token, node.size, path)]
            else -> error("Cannot traverse a scalar at JSON path: $path")
        }
    }
    return current
}

// Called only after resolving and validating the complete path.
private fun replaceAt(node: JsonElement, tokens: List<String>, value: JsonElement, depth: Int = 0): JsonElement {
    if (depth == tokens.size) return value
    val token = tokens[depth]
    return when (node) {
        is JsonObject -> node.with(token, replaceAt(node.getValue(token), tokens, value, depth + 1))
        is JsonArray -> JsonArray(node.mapIndexed { i, child ->
            if (i == token.toInt()) replaceAt(child, tokens, value, depth + 1) else child
        })
        else -> error("Cannot replace a value inside a scalar.")
    }
}

private fun selectionPath(root: JsonElement, options: Map<String, String>): List<String> {
    require(root is JsonObject) { "Configuration root must be a JSON object." }
    val full = "hsmConfigs" in root
    val standalone = "liveLmks" in root
    require(!(full && standalone)) { "Ambiguous configuration: both hsmConfigs and liveLmks are present at root." }
    require(full || standalone) { "Unsupported configuration: expected hsmConfigs or liveLmks at root." }
    val prefix = if (full) {
        val hsmId = options["--hsm-id"]
        require(hsmId != null) { "--hsm-id is required for a full configuration." }
        val configs = root["hsmConfigs"] as? JsonArray
            ?: error("hsmConfigs must be an array.")
        val matches = configs.indices.filter {
            (configs[it] as? JsonObject)?.get("id")?.let { id ->
                id is JsonPrimitive && id.isString && id.content == hsmId
            } == true
        }
        require(matches.size == 1) { "HSM ID must match exactly one configuration (found ${matches.size})." }
        listOf("hsmConfigs", matches.single().toString(), "hsmConfig", "lmkStorage", "liveLmks")
    } else {
        require("--hsm-id" !in options) { "--hsm-id is not allowed for standalone LMK storage." }
        listOf("liveLmks")
    }
    return prefix + listOf(options.getValue("--lmk-id"), "pairs", options.getValue("--pair-id"))
}
