package logmon.report;

import logmon.model.ErrorEvent;
import logmon.model.Severity;
import logmon.optimize.IntervalReportMerger;
import logmon.optimize.RemediationScheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Builds the final summary report: "error type, occurrence frequency, and
 * severity level" per the project abstract, plus the results of the two
 * optimisation stages (bucket merging and remediation scheduling).
 */
public class ReportGenerator {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static class Summary {
        public final String signatureId;
        public final Severity severity;
        public final int frequency;

        Summary(String signatureId, Severity severity, int frequency) {
            this.signatureId = signatureId;
            this.severity = severity;
            this.frequency = frequency;
        }
    }

    /** Groups events by signature/cluster id and counts occurrences, sorted by severity then frequency. */
    public static List<Summary> summarise(List<ErrorEvent> events) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Severity> severities = new HashMap<>();
        for (ErrorEvent e : events) {
            counts.merge(e.getSignatureId(), 1, Integer::sum);
            severities.put(e.getSignatureId(), e.getSeverity());
        }
        List<Summary> summaries = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            summaries.add(new Summary(e.getKey(), severities.get(e.getKey()), e.getValue()));
        }
        summaries.sort((a, b) -> {
            int sevCmp = Integer.compare(b.severity.weight, a.severity.weight);
            return sevCmp != 0 ? sevCmp : Integer.compare(b.frequency, a.frequency);
        });
        return summaries;
    }

    public static String render(int totalLines,
                                 List<ErrorEvent> events,
                                 List<Summary> summaries,
                                 List<IntervalReportMerger.Segment> segments,
                                 RemediationScheduler.ScheduleResult schedule) {
        StringBuilder sb = new StringBuilder();
        sb.append("=========================================================\n");
        sb.append(" AUTOMATED LOG ERROR MONITORING SYSTEM - SUMMARY REPORT\n");
        sb.append("=========================================================\n\n");

        sb.append(String.format("Total log lines processed : %d%n", totalLines));
        sb.append(String.format("Total error events detected: %d%n", events.size()));
        sb.append("\n--- Error Type Summary (Type | Frequency | Severity) ---\n");
        sb.append(String.format("%-28s %10s %12s%n", "ERROR TYPE", "COUNT", "SEVERITY"));
        for (Summary s : summaries) {
            sb.append(String.format("%-28s %10d %12s%n", s.signatureId, s.frequency, s.severity));
        }

        sb.append("\n--- Optimally Merged Report Windows (Interval DP) ---\n");
        sb.append(String.format("%-8s %-8s %10s%n", "FROM", "TO", "ERR-COUNT"));
        for (IntervalReportMerger.Segment seg : segments) {
            sb.append(String.format("bucket-%-3d bucket-%-3d %10d%n", seg.startBucket, seg.endBucket, seg.totalCount));
        }

        sb.append("\n--- Prioritised Remediation Schedule (Job-Sequencing) ---\n");
        sb.append(String.format("Total scheduled severity-weighted profit: %d%n", schedule.totalProfit));
        int slot = 1;
        for (RemediationScheduler.Task t : schedule.scheduledInOrder) {
            sb.append(String.format("  Slot %-3d -> %-25s (profit=%d, deadline<=slot %d)%n",
                    slot++, t.id, t.profit, t.deadline));
        }

        sb.append("\n=========================================================\n");
        return sb.toString();
    }

    public static void writeToFile(String content, Path outFile) throws IOException {
        Files.writeString(outFile, content);
    }
}
