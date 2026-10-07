package logmon.model;

/**
 * A predefined error signature (pattern) the system searches for in log text.
 *
 * cost  - the relative computational/resource cost of actively monitoring this
 *         signature (e.g. a long regex-like phrase costs more to scan for than
 *         a short one). Used by the CO3 bitmask-DP signature selector.
 * historicalFrequency - how often this signature has fired historically. Used,
 *         together with severity, to compute the "value" of monitoring it.
 */
public class ErrorSignature {

    private final String id;
    private final String pattern;
    private final Severity severity;
    private final int cost;
    private int historicalFrequency;

    public ErrorSignature(String id, String pattern, Severity severity, int cost) {
        this.id = id;
        this.pattern = pattern;
        this.severity = severity;
        this.cost = cost;
        this.historicalFrequency = 0;
    }

    public String getId() {
        return id;
    }

    public String getPattern() {
        return pattern;
    }

    public Severity getSeverity() {
        return severity;
    }

    public int getCost() {
        return cost;
    }

    public int getHistoricalFrequency() {
        return historicalFrequency;
    }

    public void incrementFrequency() {
        historicalFrequency++;
    }

    /** Value used by the knapsack-style bitmask DP: severity weight x frequency (+1 so a
     *  never-yet-seen signature still has some baseline value). */
    public int value() {
        return severity.weight * (historicalFrequency + 1);
    }

    @Override
    public String toString() {
        return id + "[" + pattern + "]";
    }
}
