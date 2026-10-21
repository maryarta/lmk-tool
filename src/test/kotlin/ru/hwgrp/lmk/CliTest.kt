package ru.hwgrp.lmk

import java.nio.file.Files
import kotlin.test.*
import kotlinx.serialization.json.*

class CliTest {
    private val fixture = """{"extra":true,"hsmConfigs":[{"id":"hsm","hsmConfig":{"lmkStorage":{"liveLmks":{"00":{"identifier":"00","pairs":{"0":{"leftKey":[0,127,-128,-1,1,2,3,4],"rightKey":[1,2,3,4,5,6,7,8],"extra":9}},"createdAt":123}}}}},{"id":"other","extra":42}]}"""

    private fun withConfig(block: (java.nio.file.Path, Array<String>) -> Unit) {
        val file = Files.createTempFile("lmk-test", ".json")
        try {
            Files.writeString(file, fixture)
            block(file, arrayOf("--config", file.toString(), "--hsm-id", "hsm", "--lmk-id", "00", "--pair-id", "0"))
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun readsSignedBytesAsHexWithoutChangingFile() = withConfig { file, opts ->
        val output = mutableListOf<String>()
        assertEquals(0, runCli(arrayOf("read") + opts, output::add, { fail(it) }))
        assertEquals(listOf("leftKey:  007F80FF01020304", "rightKey: 0102030405060708"), output)
        assertEquals(fixture, Files.readString(file))
    }

    @Test fun writesBothPartsAndPreservesOtherData() = withConfig { file, opts ->
        assertEquals(0, runCli(arrayOf("write") + opts + arrayOf("--left", "ff 80 7f 00 ab cd ef 01", "--right", "1020304050607080"), {}, { fail(it) }))
        val expected = fixture.replace("[0,127,-128,-1,1,2,3,4]", "[-1,-128,127,0,-85,-51,-17,1]")
            .replace("[1,2,3,4,5,6,7,8]", "[16,32,48,64,80,96,112,-128]")
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(Files.readString(file)))
    }

    @Test fun rejectsInvalidInputWithoutWriting() = withConfig { file, opts ->
        val cases = listOf(
            arrayOf("write") + opts + arrayOf("--left", "FFFFFFFFFFFFFFFF", "--right", "ZZ"),
            arrayOf("write") + opts + arrayOf("--left", "00", "--right", "1020304050607080"),
            arrayOf("read") + opts.map { if (it == "hsm") "missing" else it },
            arrayOf("read") + opts.map { if (it == "00") "missing" else it },
            arrayOf("read") + opts.map { if (it == "0") "99" else it },
            arrayOf("read") + opts + arrayOf("--unknown", "x"),
            arrayOf("read") + opts + arrayOf("--pair-id", "1")
        )
        for (args in cases) {
            val errors = mutableListOf<String>()
            assertEquals(1, runCli(args, {}, errors::add))
            assertTrue(errors.isNotEmpty())
            assertEquals(fixture, Files.readString(file))
        }
    }
}
