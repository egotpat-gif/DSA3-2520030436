package logmon.model;

import java.time.LocalDateTime;

/**
 * One detected occurrence of an error inside the log stream: a specific
 * signature matched (or a fuzzy-matched cluster representative) at a
 * specific line/timestamp.
 */
public class ErrorEvent {

    private final String signatureId;
    private final Severity severity;
    private final LocalDateTime timestamp;
    private final int lineNumber;
    private final String matchedText;
    private final String detectionMethod; // KMP, RABIN-KARP, EDIT-DISTANCE, SUFFIX-DISCOVERY

    public ErrorEvent(String signatureId, Severity severity, LocalDateTime timestamp,
                       int lineNumber, String matchedText, String detectionMethod) {
        this.signatureId = signatureId;
        this.severity = severity;
        this.timestamp = timestamp;
        this.lineNumber = lineNumber;
        this.matchedText = matchedText;
        this.detectionMethod = detectionMethod;
    }

    public String getSignatureId() {
        return signatureId;
    }

    public Severity getSeverity() {
        return severity;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public String getMatchedText() {
        return matchedText;
    }

    public String getDetectionMethod() {
        return detectionMethod;
    }
}
