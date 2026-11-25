package az.youtube.transcoding;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs ffmpeg / ffprobe. stdout and stderr go to a file, never to a pipe: ffmpeg writes a lot of progress output and
 * a full pipe buffer would block it forever. The process is killed on timeout and when the thread is interrupted
 * (application shutdown), so no orphan encoder keeps running.
 */
final class ExternalProcess {

    record Result(int exitCode, String output) {

        boolean succeeded() {
            return exitCode == 0;
        }

        /** The last lines, for error messages: ffmpeg prints the actual reason at the end. */
        String tail(int lines) {
            List<String> all = output.lines().toList();
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        }
    }

    private ExternalProcess() {
    }

    /**
     * @throws TranscodingException if the program cannot be started, times out or the thread is interrupted
     */
    static Result run(List<String> command, Duration timeout, Path outputFile) {
        return run(command, timeout, outputFile, null);
    }

    /** @param workingDir the process's current directory, or null for this process's */
    static Result run(List<String> command, Duration timeout, Path outputFile, Path workingDir) {
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(workingDir == null ? null : workingDir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
                    .start();
        } catch (IOException e) {
            throw new TranscodingException("Cannot start " + command.getFirst() + ": " + e.getMessage(), e);
        }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                kill(process);
                throw new TranscodingException(command.getFirst() + " did not finish within " + timeout);
            }
        } catch (InterruptedException e) {
            kill(process);
            Thread.currentThread().interrupt();
            // Shutdown: the offset is not committed, so the job is delivered again after restart.
            throw new TranscodingException("Interrupted", e);
        }
        return new Result(process.exitValue(), read(outputFile));
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String read(Path file) {
        try {
            // Not Files.readString: it throws on bytes that are not valid UTF-8 (file names in a console code page).
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static java.io.File nullDevice() {
        return new java.io.File(System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
    }
}
