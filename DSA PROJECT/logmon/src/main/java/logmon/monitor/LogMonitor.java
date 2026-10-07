package logmon.monitor;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.*;
import java.util.function.Consumer;

/**
 * Supports "continuous monitoring of log files, allowing newly generated
 * errors to be detected in real time" (from the project abstract).
 *
 * Uses java.nio.file.WatchService to watch a log file's parent directory for
 * MODIFY events, then reads only the newly appended bytes (tracked via a
 * file pointer, like `tail -f`) and hands each new line to the supplied
 * callback so it can be pushed through the same detection pipeline
 * (KMP / Rabin-Karp / edit-distance) used for the initial batch scan.
 */
public class LogMonitor {

    private final Path logFile;
    private long filePointer;

    public LogMonitor(Path logFile) throws IOException {
        this.logFile = logFile;
        this.filePointer = Files.exists(logFile) ? Files.size(logFile) : 0;
    }

    /**
     * Blocks and watches the log file forever, invoking {@code onNewLine}
     * for every new line appended after monitoring started. Intended to be
     * run on its own thread.
     */
    public void watch(Consumer<String> onNewLine) throws IOException, InterruptedException {
        Path dir = logFile.toAbsolutePath().getParent();
        WatchService watcher = FileSystems.getDefault().newWatchService();
        dir.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY);

        while (true) {
            WatchKey key = watcher.take(); // blocks until an event happens
            for (WatchEvent<?> event : key.pollEvents()) {
                Path changed = (Path) event.context();
                if (changed != null && changed.toString().equals(logFile.getFileName().toString())) {
                    readNewLines(onNewLine);
                }
            }
            if (!key.reset()) {
                break;
            }
        }
    }

    /** Reads and dispatches any bytes appended since the last read. */
    private void readNewLines(Consumer<String> onNewLine) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
            long length = raf.length();
            if (length < filePointer) {
                // File was truncated/rotated - start over from the top.
                filePointer = 0;
            }
            raf.seek(filePointer);
            String line;
            while ((line = raf.readLine()) != null) {
                onNewLine.accept(line);
            }
            filePointer = raf.getFilePointer();
        }
    }
}
