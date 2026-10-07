package logmon;

import logmon.flow.EngineerAssignment;
import logmon.matching.EditDistanceMatcher;
import logmon.matching.KMPMatcher;
import logmon.matching.RabinKarpMatcher;
import logmon.matching.SuffixArrayDiscovery;
import logmon.model.ErrorEvent;
import logmon.model.ErrorSignature;
import logmon.model.LogEntry;
import logmon.model.Severity;
import logmon.monitor.LogMonitor;
import logmon.npc.ErrorCorrelationGraph;
import logmon.npc.VertexCoverApproximation;
import logmon.optimize.BitmaskSignatureSelector;
import logmon.optimize.IntervalReportMerger;
import logmon.optimize.RemediationScheduler;
import logmon.parallel.ParallelPrimitives;
import logmon.randomized.RandomizedAlgorithms;
import logmon.report.ReportGenerator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class Main {

    private static final double FUZZY_SIMILARITY_THRESHOLD = 0.82;
    private static final int BUCKET_MINUTES = 5;

    public static void main(String[] args) throws Exception {
        Path logFile = Paths.get(args.length > 0 ? args[0] : "data/sample_server.log");

        List<ErrorSignature> signatures = buildKnownSignatures();
        List<LogEntry> entries = loadLog(logFile);
        System.out.println("Loaded " + entries.size() + " log lines from " + logFile);

        // ---- CO2: ad-hoc single-pattern KMP lookup across the whole corpus ----
        demoKmpFullCorpusLookup(entries, "OutOfMemoryError");

        // ---- CO2: Rabin-Karp multi-pattern rolling-hash scan (primary detector) ----
        Map<String, String> patternById = new LinkedHashMap<>();
        for (ErrorSignature sig : signatures) {
            patternById.put(sig.getId(), sig.getPattern());
        }
        Map<String, ErrorSignature> sigById = new HashMap<>();
        for (ErrorSignature sig : signatures) {
            sigById.put(sig.getId(), sig);
        }

        List<ErrorEvent> events = new ArrayList<>();
        List<LogEntry> unmatched = new ArrayList<>();

        for (LogEntry entry : entries) {
            Map<String, List<Integer>> hits = RabinKarpMatcher.searchMultiple(entry.getMessage(), patternById);
            boolean matchedSomething = false;
            for (Map.Entry<String, List<Integer>> hit : hits.entrySet()) {
                if (!hit.getValue().isEmpty()) {
                    ErrorSignature sig = sigById.get(hit.getKey());
                    sig.incrementFrequency();
                    events.add(new ErrorEvent(sig.getId(), sig.getSeverity(), entry.getTimestamp(),
                            entry.getLineNumber(), entry.getMessage(), "RABIN-KARP"));
                    matchedSomething = true;
                }
            }
            if (!matchedSomething && !entry.getMessage().isBlank()) {
                unmatched.add(entry);
            }
        }

        // ---- CO1: sequence alignment (edit distance) fuzzy clustering of unmatched lines ----
        List<ErrorEvent> fuzzyEvents = clusterUnmatchedByEditDistance(unmatched);
        events.addAll(fuzzyEvents);

        // ---- CO2: suffix array + LCP array discovery of brand-new repeating patterns ----
        runSuffixDiscovery(unmatched);

        // ---- CO3: bitmask DP signature-subset selection under a monitoring budget ----
        runBitmaskSelection(signatures);

        // ---- Bucket events by time window once, reused by CO3's interval DP and CO5's correlation graph ----
        BucketingResult buckets = computeBuckets(entries, events);

        // ---- CO3: interval DP merging of time-bucketed error counts into report windows ----
        List<IntervalReportMerger.Segment> segments = runIntervalMerge(buckets);

        // ---- CO1: NP-hard-scheduling-class problem (job sequencing) for remediation priority ----
        List<ReportGenerator.Summary> summaries = ReportGenerator.summarise(events);
        RemediationScheduler.ScheduleResult schedule = runRemediationScheduling(summaries);

        // ---- CO4: network flow (Edmonds-Karp) + max-flow/min-cut duality for ticket assignment ----
        EngineerAssignment.Result assignment = runEngineerAssignment(summaries);

        // ---- CO5: NP-hard problem recognition (Vertex Cover) + provable-ratio approximation ----
        Set<String> coreMonitoringSet = runVertexCoverAnalysis(buckets);

        // ---- CO6: randomised algorithms - Las Vegas select + Monte Carlo sampling ----
        RandomizedSummary randomizedSummary = runRandomizedAnalysis(summaries, entries);

        // ---- CO6: parallel-algorithm primitives - parallel reduce + parallel prefix sum ----
        ParallelSummary parallelSummary = runParallelAnalysis(buckets, events);

        // ---- Final report ----
        String coreReport = ReportGenerator.render(entries.size(), events, summaries, segments, schedule);
        String extendedReport = renderExtendedReport(assignment, coreMonitoringSet, randomizedSummary, parallelSummary);
        String fullReport = coreReport + extendedReport;

        System.out.println(fullReport);
        Path outFile = Paths.get("out/summary_report.txt");
        Files.createDirectories(outFile.getParent());
        ReportGenerator.writeToFile(fullReport, outFile);
        System.out.println("Full report written to " + outFile.toAbsolutePath());

        // ---- Continuous / real-time monitoring demo ----
        runLiveMonitoringDemo(logFile, patternById, sigById);
    }

    // -----------------------------------------------------------------
    // Setup
    // -----------------------------------------------------------------

    private static List<ErrorSignature> buildKnownSignatures() {
        List<ErrorSignature> list = new ArrayList<>();
        list.add(new ErrorSignature("NULL_POINTER", "NullPointerException", Severity.HIGH, 3));
        list.add(new ErrorSignature("CONN_REFUSED", "Connection refused", Severity.HIGH, 4));
        list.add(new ErrorSignature("TIMEOUT", "Timeout waiting for response", Severity.MEDIUM, 5));
        list.add(new ErrorSignature("AUTH_FAILED", "Authentication failed", Severity.HIGH, 3));
        list.add(new ErrorSignature("OOM", "OutOfMemoryError", Severity.CRITICAL, 2));
        list.add(new ErrorSignature("DISK_FULL", "Disk full", Severity.CRITICAL, 2));
        list.add(new ErrorSignature("NOT_FOUND_404", "404 Not Found", Severity.LOW, 2));
        list.add(new ErrorSignature("DEPRECATED_API", "Deprecated API used", Severity.LOW, 2));
        return list;
    }

    private static List<LogEntry> loadLog(Path logFile) throws IOException {
        List<String> rawLines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        List<LogEntry> entries = new ArrayList<>();
        int lineNo = 1;
        for (String line : rawLines) {
            if (!line.isBlank()) {
                entries.add(new LogEntry(lineNo, line));
            }
            lineNo++;
        }
        return entries;
    }

    // -----------------------------------------------------------------
    // CO2 - KMP ad-hoc lookup
    // -----------------------------------------------------------------

    private static void demoKmpFullCorpusLookup(List<LogEntry> entries, String pattern) {
        StringBuilder corpus = new StringBuilder();
        for (LogEntry e : entries) {
            corpus.append(e.getMessage()).append('\n');
        }
        List<Integer> positions = KMPMatcher.search(corpus.toString(), pattern);
        System.out.println("\n[CO2 | KMP] Full-corpus lookup for \"" + pattern + "\": "
                + positions.size() + " occurrence(s) at character offsets " + positions);
    }

    // -----------------------------------------------------------------
    // CO1 - sequence alignment clustering
    // -----------------------------------------------------------------

    private static List<ErrorEvent> clusterUnmatchedByEditDistance(List<LogEntry> unmatched) {
        List<String> representatives = new ArrayList<>();
        List<Integer> clusterCounts = new ArrayList<>();
        List<ErrorEvent> fuzzyEvents = new ArrayList<>();

        for (LogEntry entry : unmatched) {
            String msg = entry.getMessage();
            int bestCluster = -1;
            double bestSim = 0.0;
            for (int i = 0; i < representatives.size(); i++) {
                double sim = EditDistanceMatcher.similarity(representatives.get(i), msg);
                if (sim > bestSim) {
                    bestSim = sim;
                    bestCluster = i;
                }
            }
            if (bestCluster != -1 && bestSim >= FUZZY_SIMILARITY_THRESHOLD) {
                clusterCounts.set(bestCluster, clusterCounts.get(bestCluster) + 1);
                fuzzyEvents.add(new ErrorEvent("FUZZY-CLUSTER-" + bestCluster, Severity.MEDIUM,
                        entry.getTimestamp(), entry.getLineNumber(), msg, "EDIT-DISTANCE"));
            } else if (msg.toUpperCase(Locale.ROOT).contains("ERROR") || msg.toUpperCase(Locale.ROOT).contains("WARN")) {
                representatives.add(msg);
                clusterCounts.add(1);
                fuzzyEvents.add(new ErrorEvent("FUZZY-CLUSTER-" + (representatives.size() - 1), Severity.MEDIUM,
                        entry.getTimestamp(), entry.getLineNumber(), msg, "EDIT-DISTANCE"));
            }
        }

        System.out.println("\n[CO1 | Edit-Distance] Formed " + representatives.size()
                + " fuzzy cluster(s) from " + unmatched.size() + " unmatched line(s) "
                + "(similarity threshold " + FUZZY_SIMILARITY_THRESHOLD + "):");
        for (int i = 0; i < representatives.size(); i++) {
            System.out.println("   FUZZY-CLUSTER-" + i + " x" + clusterCounts.get(i)
                    + "  e.g. \"" + representatives.get(i) + "\"");
        }
        return fuzzyEvents;
    }

    // -----------------------------------------------------------------
    // CO2 - suffix array / LCP discovery
    // -----------------------------------------------------------------

    private static void runSuffixDiscovery(List<LogEntry> unmatched) {
        if (unmatched.isEmpty()) {
            return;
        }
        StringBuilder corpus = new StringBuilder();
        for (LogEntry e : unmatched) {
            corpus.append(e.getMessage()).append('\u0001'); // separator unlikely to appear in log text
        }
        SuffixArrayDiscovery discovery = new SuffixArrayDiscovery(corpus.toString());
        List<SuffixArrayDiscovery.RepeatCandidate> repeats = discovery.topRepeats(5, 12);

        System.out.println("\n[CO2 | Suffix Array + LCP] Candidate NEW error signatures discovered "
                + "from previously unmatched lines:");
        if (repeats.isEmpty()) {
            System.out.println("   (no substring repeated often enough to suggest a new signature)");
        } else {
            for (SuffixArrayDiscovery.RepeatCandidate c : repeats) {
                System.out.println("   " + c);
            }
        }
    }

    // -----------------------------------------------------------------
    // CO3 - bitmask DP subset selection
    // -----------------------------------------------------------------

    private static void runBitmaskSelection(List<ErrorSignature> signatures) {
        int totalCost = signatures.stream().mapToInt(ErrorSignature::getCost).sum();
        int budget = totalCost / 2; // simulate a constrained-resource monitoring mode

        BitmaskSignatureSelector.Selection selection = BitmaskSignatureSelector.selectBest(signatures, budget);
        System.out.println("\n[CO3 | Bitmask DP] Resource-constrained monitoring mode (budget=" + budget
                + " of max " + totalCost + "):");
        System.out.println("   Selected signatures (total cost=" + selection.totalCost
                + ", total value=" + selection.totalValue + "):");
        for (ErrorSignature s : selection.chosen) {
            System.out.println("     " + s + " severity=" + s.getSeverity()
                    + " freq=" + s.getHistoricalFrequency() + " cost=" + s.getCost());
        }
    }

    // -----------------------------------------------------------------
    // Bucketing (shared by CO3 interval-merge and CO5 correlation graph)
    // -----------------------------------------------------------------

    private static class BucketingResult {
        final int[] bucketCounts;
        final Map<Integer, List<ErrorEvent>> eventsByBucket;

        BucketingResult(int[] bucketCounts, Map<Integer, List<ErrorEvent>> eventsByBucket) {
            this.bucketCounts = bucketCounts;
            this.eventsByBucket = eventsByBucket;
        }
    }

    private static BucketingResult computeBuckets(List<LogEntry> entries, List<ErrorEvent> events) {
        LocalDateTime start = entries.get(0).getTimestamp();
        long maxMinutes = 0;
        for (ErrorEvent e : events) {
            long minutes = ChronoUnit.MINUTES.between(start, e.getTimestamp());
            maxMinutes = Math.max(maxMinutes, minutes);
        }
        int bucketCount = (int) (maxMinutes / BUCKET_MINUTES) + 1;
        int[] bucketCounts = new int[bucketCount];
        Map<Integer, List<ErrorEvent>> eventsByBucket = new HashMap<>();
        for (ErrorEvent e : events) {
            long minutes = ChronoUnit.MINUTES.between(start, e.getTimestamp());
            int bucket = (int) (minutes / BUCKET_MINUTES);
            bucketCounts[bucket]++;
            eventsByBucket.computeIfAbsent(bucket, k -> new ArrayList<>()).add(e);
        }
        return new BucketingResult(bucketCounts, eventsByBucket);
    }

    // -----------------------------------------------------------------
    // CO3 - interval DP report-window merging
    // -----------------------------------------------------------------

    private static List<IntervalReportMerger.Segment> runIntervalMerge(BucketingResult buckets) {
        IntervalReportMerger merger = new IntervalReportMerger(buckets.bucketCounts);
        List<IntervalReportMerger.Segment> segments = merger.optimalSegments();
        System.out.println("\n[CO3 | Interval DP] " + buckets.bucketCounts.length + " time-buckets (each "
                + BUCKET_MINUTES + " min) merged into " + segments.size() + " report window(s), min total cost="
                + merger.minimumCost());
        return segments;
    }

    // -----------------------------------------------------------------
    // CO1 - NP-hard scheduling class (job sequencing) for remediation
    // -----------------------------------------------------------------

    private static RemediationScheduler.ScheduleResult runRemediationScheduling(
            List<ReportGenerator.Summary> summaries) {
        List<RemediationScheduler.Task> tasks = new ArrayList<>();
        for (ReportGenerator.Summary s : summaries) {
            int deadline = switch (s.severity) {
                case CRITICAL -> 1;
                case HIGH -> 2;
                case MEDIUM -> 3;
                case LOW -> 4;
            };
            int profit = s.severity.weight * s.frequency;
            tasks.add(new RemediationScheduler.Task(s.signatureId, profit, deadline));
        }
        RemediationScheduler.ScheduleResult result = RemediationScheduler.schedule(tasks);
        System.out.println("\n[CO1 | Job-Sequencing / NP-hard-scheduling class] "
                + result.scheduledInOrder.size() + " of " + tasks.size()
                + " candidate remediation tasks fit into the available slots "
                + "(total profit=" + result.totalProfit + ")");
        return result;
    }

    // -----------------------------------------------------------------
    // CO4 - network flow (Edmonds-Karp) + max-flow/min-cut duality
    // -----------------------------------------------------------------

    private static EngineerAssignment.Result runEngineerAssignment(List<ReportGenerator.Summary> summaries) {
        Map<String, String> categoryOf = new HashMap<>();
        categoryOf.put("NULL_POINTER", "BACKEND");
        categoryOf.put("TIMEOUT", "BACKEND");
        categoryOf.put("CONN_REFUSED", "INFRA");
        categoryOf.put("OOM", "INFRA");
        categoryOf.put("DISK_FULL", "INFRA");
        categoryOf.put("AUTH_FAILED", "SECURITY");
        categoryOf.put("NOT_FOUND_404", "MISC");
        categoryOf.put("DEPRECATED_API", "MISC");
        // Any FUZZY-CLUSTER-* id not listed above falls back to "MISC" via getOrDefault.

        List<EngineerAssignment.Engineer> engineers = List.of(
                new EngineerAssignment.Engineer("Asha (backend)", 3, Set.of("BACKEND")),
                new EngineerAssignment.Engineer("Ravi (infra)", 2, Set.of("INFRA")),
                new EngineerAssignment.Engineer("Kiran (security)", 2, Set.of("SECURITY")),
                new EngineerAssignment.Engineer("Priya (on-call/misc)", 4, Set.of("MISC", "BACKEND", "INFRA", "SECURITY"))
        );

        List<String> demandIds = new ArrayList<>();
        List<Integer> demandCounts = new ArrayList<>();
        for (ReportGenerator.Summary s : summaries) {
            demandIds.add(s.signatureId);
            demandCounts.add(s.frequency);
        }

        EngineerAssignment.Result result = EngineerAssignment.assign(demandIds, demandCounts, categoryOf, engineers);

        System.out.println("\n[CO4 | Network Flow / Edmonds-Karp] Ticket assignment: "
                + result.totalAssigned + " of " + result.totalDemand + " error tickets assignable "
                + "within today's engineer capacities.");
        for (Map.Entry<String, Integer> e : result.assignedPerEngineer.entrySet()) {
            System.out.println("     " + e.getKey() + " -> " + e.getValue() + " ticket(s) assigned");
        }
        if (result.bottleneckEngineers.isEmpty()) {
            System.out.println("   No engineer capacity is fully saturated (no bottleneck in the min cut).");
        } else {
            System.out.println("   Min-cut bottleneck(s) (max-flow/min-cut duality - capacity fully used): "
                    + result.bottleneckEngineers);
        }
        return result;
    }

    // -----------------------------------------------------------------
    // CO5 - NP-hard problem recognition (Vertex Cover) + approximation
    // -----------------------------------------------------------------

    private static Set<String> runVertexCoverAnalysis(BucketingResult buckets) {
        ErrorCorrelationGraph graph = new ErrorCorrelationGraph();
        for (List<ErrorEvent> bucketEvents : buckets.eventsByBucket.values()) {
            Set<String> distinctTypesInBucket = new LinkedHashSet<>();
            for (ErrorEvent e : bucketEvents) {
                distinctTypesInBucket.add(e.getSignatureId());
            }
            List<String> typeList = new ArrayList<>(distinctTypesInBucket);
            for (int i = 0; i < typeList.size(); i++) {
                for (int j = i + 1; j < typeList.size(); j++) {
                    graph.addCooccurrence(typeList.get(i), typeList.get(j));
                }
            }
        }

        Set<String> cover = VertexCoverApproximation.approximate(graph);
        System.out.println("\n[CO5 | NP-hard recognition + 2-approx Vertex Cover] Correlation graph has "
                + graph.nodes().size() + " error-type node(s) and " + graph.edgeCount() + " correlation edge(s).");
        System.out.println("   Minimal-effort monitoring core set (guaranteed <= 2x optimal size): " + cover);
        return cover;
    }

    // -----------------------------------------------------------------
    // CO6 - randomised algorithms (Las Vegas + Monte Carlo)
    // -----------------------------------------------------------------

    private static class RandomizedSummary {
        final double medianFrequency;
        final double monteCarloEstimatedRate;
        final double actualErrorRate;

        RandomizedSummary(double medianFrequency, double monteCarloEstimatedRate, double actualErrorRate) {
            this.medianFrequency = medianFrequency;
            this.monteCarloEstimatedRate = monteCarloEstimatedRate;
            this.actualErrorRate = actualErrorRate;
        }
    }

    private static RandomizedSummary runRandomizedAnalysis(List<ReportGenerator.Summary> summaries,
                                                             List<LogEntry> entries) {
        int[] frequencies = summaries.stream().mapToInt(s -> s.frequency).toArray();
        double median = RandomizedAlgorithms.medianOf(frequencies);

        List<String> rawMessages = new ArrayList<>();
        int actualErrorLines = 0;
        for (LogEntry e : entries) {
            rawMessages.add(e.getMessage());
            if (e.getMessage().toUpperCase(Locale.ROOT).contains("ERROR")) {
                actualErrorLines++;
            }
        }
        double actualRate = entries.isEmpty() ? 0 : (double) actualErrorLines / entries.size();
        double sampleFraction = 0.4;
        double estimatedRate = RandomizedAlgorithms.monteCarloErrorRateEstimate(rawMessages, sampleFraction,
                line -> line.toUpperCase(Locale.ROOT).contains("ERROR"));

        System.out.println("\n[CO6 | Randomised Algorithms]");
        System.out.println("   Las Vegas (randomised-select): median error-type frequency = " + median
                + " (always exact, expected O(n) time)");
        System.out.printf("   Monte Carlo (%.0f%% random sample): estimated error-line rate = %.3f "
                        + "(actual = %.3f, sampling error = %.3f)%n",
                sampleFraction * 100, estimatedRate, actualRate, Math.abs(estimatedRate - actualRate));

        return new RandomizedSummary(median, estimatedRate, actualRate);
    }

    // -----------------------------------------------------------------
    // CO6 - parallel-algorithm primitives (parallel reduce + prefix sum)
    // -----------------------------------------------------------------

    private static class ParallelSummary {
        final long totalSeverityWeightedScore;
        final long[] cumulativeBucketCounts;

        ParallelSummary(long totalSeverityWeightedScore, long[] cumulativeBucketCounts) {
            this.totalSeverityWeightedScore = totalSeverityWeightedScore;
            this.cumulativeBucketCounts = cumulativeBucketCounts;
        }
    }

    private static ParallelSummary runParallelAnalysis(BucketingResult buckets, List<ErrorEvent> events) {
        int[] severityWeights = new int[events.size()];
        for (int i = 0; i < events.size(); i++) {
            severityWeights[i] = events.get(i).getSeverity().weight;
        }
        long totalScore = ParallelPrimitives.parallelReduceSum(severityWeights);
        long[] cumulative = ParallelPrimitives.parallelPrefixSum(buckets.bucketCounts);

        System.out.println("\n[CO6 | Parallel Primitives] parallel-reduce total severity-weighted score = "
                + totalScore + " (work O(n), span O(log n))");
        System.out.println("   parallel-prefix-sum of bucketed error counts (cumulative, work O(n log n), span O(log^2 n)): "
                + Arrays.toString(cumulative));

        return new ParallelSummary(totalScore, cumulative);
    }

    // -----------------------------------------------------------------
    // Extended report section for CO4-CO6
    // -----------------------------------------------------------------

    private static String renderExtendedReport(EngineerAssignment.Result assignment,
                                                Set<String> coreMonitoringSet,
                                                RandomizedSummary randomizedSummary,
                                                ParallelSummary parallelSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n--- CO4: Network-Flow Ticket Assignment (Edmonds-Karp) ---\n");
        sb.append(String.format("Total tickets assignable: %d / %d%n", assignment.totalAssigned, assignment.totalDemand));
        for (Map.Entry<String, Integer> e : assignment.assignedPerEngineer.entrySet()) {
            sb.append(String.format("  %-22s %d ticket(s)%n", e.getKey(), e.getValue()));
        }
        sb.append("Bottleneck engineer(s) (min cut): ")
                .append(assignment.bottleneckEngineers.isEmpty() ? "none" : assignment.bottleneckEngineers)
                .append('\n');

        sb.append("\n--- CO5: NP-Hard Vertex Cover (2-approximation) ---\n");
        sb.append("Minimal-effort monitoring core set: ").append(coreMonitoringSet).append('\n');

        sb.append("\n--- CO6: Randomised Algorithms ---\n");
        sb.append(String.format("Median error-type frequency (Las Vegas randomised-select): %.2f%n",
                randomizedSummary.medianFrequency));
        sb.append(String.format("Monte Carlo estimated error-line rate: %.3f (actual: %.3f)%n",
                randomizedSummary.monteCarloEstimatedRate, randomizedSummary.actualErrorRate));

        sb.append("\n--- CO6: Parallel Primitives (Fork/Join) ---\n");
        sb.append("Total severity-weighted score (parallel reduce): ")
                .append(parallelSummary.totalSeverityWeightedScore).append('\n');
        sb.append("Cumulative bucket counts (parallel prefix sum): ")
                .append(Arrays.toString(parallelSummary.cumulativeBucketCounts)).append('\n');

        sb.append("\n=========================================================\n");
        return sb.toString();
    }

    // -----------------------------------------------------------------
    // Continuous / real-time monitoring demo
    // -----------------------------------------------------------------

    private static void runLiveMonitoringDemo(Path originalLog, Map<String, String> patternById,
                                               Map<String, ErrorSignature> sigById) throws Exception {
        Path liveFile = Paths.get("out/live_demo.log");
        Files.createDirectories(liveFile.getParent());
        Files.copy(originalLog, liveFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        System.out.println("\n[Continuous Monitoring] Watching " + liveFile
                + " for newly appended log lines (real-time demo, ~3s)...");

        LogMonitor monitor = new LogMonitor(liveFile);
        Thread watcherThread = new Thread(() -> {
            try {
                monitor.watch(line -> {
                    LogEntry entry = new LogEntry(0, line);
                    Map<String, List<Integer>> hits = RabinKarpMatcher.searchMultiple(entry.getMessage(), patternById);
                    for (Map.Entry<String, List<Integer>> hit : hits.entrySet()) {
                        if (!hit.getValue().isEmpty()) {
                            ErrorSignature sig = sigById.get(hit.getKey());
                            System.out.println("   [LIVE DETECTED] " + sig.getId()
                                    + " (" + sig.getSeverity() + ") in line: " + line.trim());
                        }
                    }
                });
            } catch (Exception ignored) {
                // Watcher thread ends when the JVM exits (daemon thread).
            }
        });
        watcherThread.setDaemon(true);
        watcherThread.start();

        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        Thread.sleep(700);
        Files.writeString(liveFile,
                "\n" + LocalDateTime.now().withNano(0).format(fmt)
                        + " ERROR NullPointerException at com.app.LiveDemo.run(LiveDemo.java:9)\n",
                StandardOpenOption.APPEND);

        Thread.sleep(700);
        Files.writeString(liveFile,
                LocalDateTime.now().withNano(0).format(fmt)
                        + " ERROR OutOfMemoryError: Java heap space in worker-thread-9\n",
                StandardOpenOption.APPEND);

        Thread.sleep(1200);
        System.out.println("[Continuous Monitoring] Demo finished.");
    }
}
