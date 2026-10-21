package ru.hwgrp.lmk

import kotlin.test.*
import java.nio.file.Files

class InteractiveTest {
    @Test fun sessionContinuesAfterErrorsAndQuitsOnExit() {
        val input = listOf("", "nonsense", "help", "read --config 'unterminated", "exit", "nonsense").iterator()
        val output = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var prompts = 0
        runInteractive({ if (input.hasNext()) input.next() else null }, output::add, errors::add, { prompts++ })
        assertEquals(5, prompts)
        assertEquals(2, errors.size)
        assertTrue(output.any { it.contains("--hsm-id") })
        assertTrue(input.hasNext())
    }

    @Test fun endOfInputExitsCleanly() {
        var prompts = 0
        runInteractive({ null }, {}, { fail(it) }, { prompts++ })
        assertEquals(1, prompts)
    }

    @Test fun readsAndWritesWithQuotedPathsAndHexInOneSession() {
        val directory = Files.createTempDirectory("lmk interactive ")
        val file = directory.resolve("config file.json")
        try {
            Files.writeString(file, """{"hsmConfigs":[{"id":"hsm","hsmConfig":{"lmkStorage":{"liveLmks":{"00":{"pairs":{"0":{"leftKey":[0,0,0,0,0,0,0,0],"rightKey":[0,0,0,0,0,0,0,0]}}}}}}}]}""")
            val options = "--config \"$file\" --hsm-id hsm --lmk-id 00 --pair-id 0"
            val lines = listOf("write $options --left 'ff 80 7f 00 ab cd ef 01' --right 1020304050607080", "read $options", "quit").iterator()
            val output = mutableListOf<String>()
            runInteractive({ if (lines.hasNext()) lines.next() else null }, output::add, { fail(it) }, {})
            assertTrue("leftKey:  FF807F00ABCDEF01" in output)
            assertTrue("rightKey: 1020304050607080" in output)
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }
}
