package com.example.fakeclaude;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 供大模型调用的工具集合。
 *
 * <p>shell 工具仅运行固定的只读命令；文件工具只能写入受控根目录。</p>
 */
@Service
public class ToolService {

    /** 返回给模型的输出上限，避免几千行日志把上下文撑爆 */
    private static final int MAX_OUTPUT_BYTES = 20_000;

    /** 单次写入的内容上限，防止模型一口气灌进一个几百 KB 的文件 */
    private static final int MAX_CONTENT_CHARS = 200_000;

    /**
     * 只有这些无 shell 元字符的只读命令可以由模型直接运行。需要修改系统或执行任意命令时，
     * 用户应在自己的终端中执行，而不是把权限交给模型。
     */
    private static final List<Pattern> READ_ONLY_COMMANDS = List.of(
            Pattern.compile("(?i)dir(?:\\s+[/a-z0-9._-]+)*"),
            Pattern.compile("(?i)git\\s+--version"),
            Pattern.compile("(?i)java\\s+-version"),
            Pattern.compile("(?i)echo(?: [a-z0-9 ._/-]+)?")
    );

    private final Path defaultWorkDir;
    private final Path baseDir;
    private final long timeoutSeconds;
    private final Charset charset;
    private final boolean windows;

    public ToolService(
            @Value("${fake-claude.shell.work-dir:${user.dir}}") String workDir,
            @Value("${fake-claude.fs.base-dir:${user.dir}}") String baseDir,
            @Value("${fake-claude.shell.timeout-seconds:60}") long timeoutSeconds,
            @Value("${fake-claude.shell.charset:}") String charsetName) {

        this.baseDir = resolveExistingDirectory(Path.of(baseDir), "文件根目录");
        this.defaultWorkDir = resolveExistingDirectory(Path.of(workDir), "默认工作目录");
        if (!this.defaultWorkDir.startsWith(this.baseDir)) {
            throw new IllegalArgumentException("默认工作目录必须位于文件根目录内：" + this.defaultWorkDir);
        }
        if (timeoutSeconds <= 0 || timeoutSeconds > 300) {
            throw new IllegalArgumentException("shell 超时必须在 1 到 300 秒之间");
        }
        this.timeoutSeconds = timeoutSeconds;
        this.windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        // Windows 控制台默认是 GBK，Java 读进程输出时按 UTF-8 解会乱码，所以按系统默认给个合理值
        this.charset = (charsetName == null || charsetName.isBlank())
                ? (windows ? Charset.forName("GBK") : StandardCharsets.UTF_8)
                : Charset.forName(charsetName);
    }

    @Tool(description = "在受控工作目录中执行允许列表内的只读 shell 命令并返回退出码和输出。"
            + "仅支持 dir、git --version、java -version 和 echo；"
            + "构建、脚本、修改文件、联网、安装依赖、删除文件及其他任意命令均会被拒绝，需由用户自行在终端执行。")
    public String shellCommand(
            @ToolParam(description = "允许的只读命令，例如：dir、git --version、java -version") String command) {
        return execute(command, defaultWorkDir);
    }

    @Tool(description = "在受控文件根目录内的指定工作目录执行允许列表内的只读命令。")
    public String shellCommandIn(
            @ToolParam(description = "要执行的命令") String command,
            @ToolParam(description = "工作目录的绝对路径") String directory) {

        if (directory == null || directory.isBlank()) {
            return "工作目录为空。";
        }
        try {
            Path dir = resolveExistingDirectory(Path.of(directory), "工作目录");
            if (!dir.startsWith(baseDir)) {
                return "拒绝使用工作目录之外的路径：" + dir;
            }
            return execute(command, dir);
        } catch (InvalidPathException e) {
            return "路径不合法：" + directory;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    @Tool(description = "把文本内容写入文件（覆盖写入）。父目录不存在时自动创建；"
            + "目标文件已存在时，会先备份成同名 .bak 再覆盖。"
            + "只能写入配置的工作目录之内，越界会被拒绝。")
    public String writeFile(
            @ToolParam(description = "文件的绝对路径，例如 C:\\work_space\\idea\\fake-claude\\docs\\note.md") String path,
            @ToolParam(description = "要写入的完整文本内容") String content) {
        return write(path, content, false);
    }

    @Tool(description = "把文本内容追加到文件末尾；文件不存在时等价于新建。同样只能写入工作目录之内。")
    public String appendToFile(
            @ToolParam(description = "文件的绝对路径") String path,
            @ToolParam(description = "要追加的文本内容") String content) {
        return write(path, content, true);
    }

    private String write(String path, String content, boolean append) {
        if (path == null || path.isBlank()) {
            return "路径为空，未写入。";
        }
        if (content == null || content.isBlank()) {
            return "内容为空，未写入。";
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            return "内容过大（" + content.length() + " 字符，上限 " + MAX_CONTENT_CHARS + "），未写入。";
        }

        Path target;
        try {
            target = Path.of(path).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return "路径不合法：" + path;
        }

        // 先做词法检查，再逐级解析真实目录；后者会发现符号链接和 Windows junction 指向根目录外的情况。
        if (!target.startsWith(baseDir)) {
            return "拒绝写入工作目录之外的文件：" + target + "\n（允许的根目录：" + baseDir + "）";
        }
        if (target.startsWith(baseDir.resolve(".git"))) {
            return "拒绝写入 .git 目录：" + target;
        }

        try {
            Path parent = target.getParent();
            if (parent == null) {
                return "目标路径没有父目录：" + target;
            }
            Path safeParent = createAndResolveParent(parent);
            target = safeParent.resolve(target.getFileName());

            boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            if (exists && (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(target))) {
                return "目标已存在，但不是普通文件：" + target;
            }
            if (exists && !target.toRealPath().startsWith(baseDir)) {
                return "拒绝写入解析后位于工作目录之外的文件：" + target;
            }

            String backup = null;
            if (exists && !append) {
                Path bak = target.resolveSibling(target.getFileName() + ".bak");
                if (Files.exists(bak, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(bak)) {
                    return "拒绝覆盖符号链接备份文件：" + bak;
                }
                Files.copy(target, bak, StandardCopyOption.REPLACE_EXISTING);
                backup = bak.toString();
            }

            if (append) {
                Files.writeString(target, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.writeString(target, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            }

            System.out.println((append ? "追加写入：" : "写入文件：") + target);

            return "[成功] " + (append ? "已追加到 " : "已写入 ") + target + "\n"
                    + "[模式] " + (exists ? (append ? "追加到已有文件" : "覆盖（已备份 .bak）") : "新建文件") + "\n"
                    + "[大小] " + content.length() + " 字符 / " + content.lines().count() + " 行"
                    + (backup == null ? "" : "\n[备份] " + backup);

        } catch (IOException e) {
            return "写入失败：" + e.getMessage();
        }
    }

    private String execute(String command, Path workDir) {
        if (command == null || command.isBlank()) {
            return "命令为空，未执行。";
        }
        if (!isAllowedReadOnlyCommand(command)) {
            return "拒绝执行：该工具只允许预定义的只读命令；请让用户在本机终端确认并自行执行此命令。";
        }
        System.out.println("执行受限命令：" + command + "   [工作目录] " + workDir);

        List<String> wrapper = windows
                ? List.of("cmd.exe", "/c", command)
                : List.of("/bin/sh", "-c", command);

        ProcessBuilder builder = new ProcessBuilder(wrapper);
        builder.directory(workDir.toFile());
        builder.redirectErrorStream(true); // stdout + stderr 合并，模型能同时看到报错

        Instant start = Instant.now();
        try {
            Process process = builder.start();

            // 边跑边把输出抽干，避免管道缓冲区写满导致子进程卡死
            OutputCapture output = new OutputCapture();
            Thread pump = new Thread(() -> {
                try (InputStream in = process.getInputStream()) {
                    byte[] chunk = new byte[8_192];
                    int count;
                    while ((count = in.read(chunk)) != -1) {
                        output.append(chunk, count);
                    }
                } catch (IOException ignored) {
                    // 进程被强杀时流会断开，属于预期情况
                }
            }, "shell-output-pump");
            pump.setDaemon(true);
            pump.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                // Windows 上 cmd.exe 被杀后它的子进程还会继续跑并占着管道，所以连子进程树一起杀
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                pump.join(2_000);
                return format(command, workDir, -1, elapsed(start),
                        text(output) + "\n[已超时] 执行超过 " + timeoutSeconds + " 秒，进程已被强制终止。");
            }

            pump.join(5_000);
            return format(command, workDir, process.exitValue(), elapsed(start), text(output));

        } catch (IOException e) {
            return "命令启动失败：" + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "命令执行被中断。";
        }
    }

    private boolean isAllowedReadOnlyCommand(String command) {
        return READ_ONLY_COMMANDS.stream().anyMatch(pattern -> pattern.matcher(command).matches());
    }

    private Path resolveExistingDirectory(Path directory, String label) {
        try {
            if (!Files.isDirectory(directory)) {
                throw new IllegalArgumentException(label + "不存在或不是文件夹：" + directory.toAbsolutePath().normalize());
            }
            return directory.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("无法解析" + label + "：" + directory, e);
        }
    }

    private Path createAndResolveParent(Path parent) throws IOException {
        Path normalizedParent = parent.toAbsolutePath().normalize();
        if (!normalizedParent.startsWith(baseDir)) {
            throw new IOException("父目录位于允许根目录之外：" + normalizedParent);
        }
        Path current = baseDir;
        for (Path segment : baseDir.relativize(normalizedParent)) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(current)
                        || !current.toRealPath().startsWith(baseDir)) {
                    throw new IOException("拒绝使用符号链接、junction 或非目录的父路径：" + current);
                }
            } else {
                Files.createDirectory(current);
            }
        }
        Path resolved = normalizedParent.toRealPath();
        if (!resolved.startsWith(baseDir)) {
            throw new IOException("父目录解析后位于允许根目录之外：" + resolved);
        }
        return resolved;
    }

    private String text(OutputCapture buffer) {
        String output = buffer.asString(charset).replace("\r\n", "\n");
        if (buffer.wasTruncated()) {
            output += "\n...[输出已在 " + MAX_OUTPUT_BYTES + " 字节处截断]";
        }
        return output.isBlank() ? "(无输出)" : output;
    }

    static final class OutputCapture {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final AtomicBoolean truncated = new AtomicBoolean();

        synchronized void append(byte[] bytes, int count) {
            int remaining = MAX_OUTPUT_BYTES - buffer.size();
            if (remaining > 0) {
                buffer.write(bytes, 0, Math.min(remaining, count));
            }
            if (count > remaining) {
                truncated.set(true);
            }
        }

        synchronized String asString(Charset charset) {
            return buffer.toString(charset);
        }

        boolean wasTruncated() {
            return truncated.get();
        }
    }

    private String format(String command, Path workDir, int exitCode, String elapsed, String output) {
        return "$ " + command + "\n"
                + "[工作目录] " + workDir + "\n"
                + "[退出码] " + (exitCode < 0 ? "无（超时终止）" : exitCode) + "\n"
                + "[耗时] " + elapsed + "\n"
                + "[输出]\n" + output;
    }

    private String elapsed(Instant start) {
        long millis = Duration.between(start, Instant.now()).toMillis();
        return millis < 1000 ? millis + " ms" : String.format("%.2f s", millis / 1000.0);
    }
}
