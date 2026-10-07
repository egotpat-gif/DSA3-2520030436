package logmon.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * A single line of a server log file, parsed into a timestamp and message.
 * Expected raw format: "yyyy-MM-dd HH:mm:ss LEVEL message text..."
 */
public class LogEntry {

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final int lineNumber;
    private final LocalDateTime timestamp;
    private final String rawLine;
    private final String message;

    public LogEntry(int lineNumber, String rawLine) {
        this.lineNumber = lineNumber;
        this.rawLine = rawLine;

        LocalDateTime ts;
        String msg;
        try {
            String tsPart = rawLine.substring(0, 19);
            ts = LocalDateTime.parse(tsPart, TS_FORMAT);
            msg = rawLine.length() > 20 ? rawLine.substring(20) : "";
        } catch (Exception e) {
            // Line does not start with a valid timestamp; treat whole line as message.
            ts = LocalDateTime.MIN;
            msg = rawLine;
        }
        this.timestamp = ts;
        this.message = msg;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public String getRawLine() {
        return rawLine;
    }

    public String getMessage() {
        return message;
    }
}
