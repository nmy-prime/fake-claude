package com.example.fakeclaude;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolServiceTest {

    @TempDir
    Path root;

    @Test
    void writesOnlyInsideConfiguredRoot() throws Exception {
        ToolService service = service();
        Path target = root.resolve("notes/example.txt");

        String result = service.writeFile(target.toString(), "hello");

        assertTrue(result.startsWith("[成功]"));
        assertTrue(Files.exists(target));
        assertEquals("hello", Files.readString(target, StandardCharsets.UTF_8));
        assertTrue(service.writeFile(root.resolve("../outside.txt").toString(), "nope")
                .startsWith("拒绝写入工作目录之外"));
    }

    @Test
    void rejectsCommandsOutsideReadOnlyAllowList() {
        String result = service().shellCommand("whoami");

        assertTrue(result.startsWith("拒绝执行"));
    }

    @Test
    void capsShellOutputWhileCapturingProcessStream() {
        ToolService.OutputCapture capture = new ToolService.OutputCapture();
        capture.append("x".repeat(25_000).getBytes(StandardCharsets.UTF_8), 25_000);

        assertTrue(capture.wasTruncated());
        assertTrue(capture.asString(StandardCharsets.UTF_8).length() == 20_000);
    }

    private ToolService service() {
        return new ToolService(root.toString(), root.toString(), 10, "UTF-8");
    }
}
