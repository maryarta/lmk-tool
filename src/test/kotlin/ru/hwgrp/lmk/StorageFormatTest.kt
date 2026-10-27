package ru.hwgrp.lmk

import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class StorageFormatTest {
    private val pair = """{"leftKey":[0,127,-128,-1,1,2,3,4],"rightKey":[1,2,3,4,5,6,7,8],"extra":true}"""
    private val storage get() = """{"liveLmks":{"00":{"pairs":{"0":$pair}}},"extra":123}"""
    private fun withFile(content: String, block: (java.nio.file.Path) -> Unit) {
        val file = Files.createTempFile("lmk-format", ".json")
        try { Files.writeString(file, content); block(file) }
        finally { Files.deleteIfExists(file) }
    }
    @Test fun readsAndWritesBothFormatsPreservingOtherData() {
        val full = """{"hsmConfigs":[{"id":"other"},{"id":"selected","hsmConfig":{"lmkStorage":$storage}}]}"""
        for ((content, selector) in listOf(storage to emptyArray<String>(), full to arrayOf("--hsm-id", "selected"))) {
            withFile(content) { file ->
                val args = arrayOf("--config", file.toString(), "--lmk-id", "00", "--pair-id", "0") + selector
                val output = mutableListOf<String>()
                assertEquals(0, runCli(arrayOf("read") + args, output::add, { fail(it) }))
                assertEquals("leftKey:  007F80FF01020304", output[0])
                assertEquals(content, Files.readString(file))
                assertEquals(0, runCli(arrayOf("write") + args + arrayOf("--left", "FFFFFFFFFFFFFFFF", "--right", "8080808080808080"), {}, { fail(it) }))
                val expected = content.replace("[0,127,-128,-1,1,2,3,4]", "[-1,-1,-1,-1,-1,-1,-1,-1]").replace("[1,2,3,4,5,6,7,8]", "[-128,-128,-128,-128,-128,-128,-128,-128]")
                assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(Files.readString(file)))
            }
        }
    }
    @Test fun rejectsMismatchedOptionsAndStructuresWithoutWriting() {
        val cases = listOf(
            Triple(storage, arrayOf("--hsm-id", "x"), "not allowed"),
            Triple("""{"hsmConfigs":[]}""", emptyArray<String>(), "required"),
            Triple(storage, arrayOf("--path", "/liveLmks/00/pairs/0"), "Unknown option"),
            Triple("{}", emptyArray<String>(), "Unsupported"),
            Triple("[]", emptyArray<String>(), "object"),
            Triple("""{"hsmConfigs":[],"liveLmks":{}}""", emptyArray<String>(), "Ambiguous"),
            Triple("""{"liveLmks":{}}""", emptyArray<String>(), "not found"),
            Triple("""{"liveLmks":{"00":{"pairs":{"0":{}}}}}""", emptyArray<String>(), "leftKey")
        )
        for ((content, extra, message) in cases) withFile(content) { file ->
            val errors = mutableListOf<String>()
            val args = arrayOf("write", "--config", file.toString(), "--lmk-id", "00", "--pair-id", "0", "--left", "FFFFFFFFFFFFFFFF", "--right", "FFFFFFFFFFFFFFFF") + extra
            assertEquals(1, runCli(args, {}, errors::add))
            assertTrue(errors.single().contains(message), errors.toString())
            assertEquals(content, Files.readString(file))
        }
    }
}
