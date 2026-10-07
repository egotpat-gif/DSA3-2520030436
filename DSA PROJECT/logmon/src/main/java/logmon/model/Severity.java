package logmon.model;

/**
 * Severity levels used to classify detected error signatures.
 * weight is used by the optimisation modules (CO3) as the "value"
 * of catching an occurrence of that severity.
 */
public enum Severity {
    CRITICAL(4),
    HIGH(3),
    MEDIUM(2),
    LOW(1);

    public final int weight;

    Severity(int weight) {
        this.weight = weight;
    }
}
