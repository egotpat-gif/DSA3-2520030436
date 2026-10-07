package logmon;

import logmon.flow.EngineerAssignment;
import logmon.flow.NetworkFlowAssigner;
import logmon.matching.EditDistanceMatcher;
import logmon.matching.KMPMatcher;
import logmon.matching.RabinKarpMatcher;
import logmon.matching.SuffixArrayDiscovery;
import logmon.model.ErrorSignature;
import logmon.model.Severity;
import logmon.npc.ErrorCorrelationGraph;
import logmon.npc.VertexCoverApproximation;
import logmon.optimize.BitmaskSignatureSelector;
import logmon.optimize.IntervalReportMerger;
import logmon.optimize.RemediationScheduler;
import logmon.parallel.ParallelPrimitives;
import logmon.randomized.RandomizedAlgorithms;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Plain-Java test runner (no JUnit / no external libraries) so the whole
 * project can be demonstrated with a single command. Each test feeds a tiny
 * input whose correct answer can be verified by hand, then prints PASS/FAIL.
 *
 * Run from the project root:   java -cp out/classes logmon.TestRunner
 */
public class TestRunner {

    private interface Body {
        void run() throws Exception;
    }

    private static int passed = 0;
    private static int failed = 0;

    private static void test(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("  [PASS] " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("  [FAIL] " + name + "  -> " + t);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void checkEquals(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(what + ": expected " + expected + " but got " + actual);
        }
    }

    private static void section(String title) {
        System.out.println("\n== " + title + " ==");
    }

    public static void main(String[] args) {
        System.out.println("Automated Log Error Monitoring System - unit tests");

        // -------------------------------------------------------------
        section("CO1 / CO2: string matching");
        // -------------------------------------------------------------
        test("KMP finds overlapping matches: 'aba' in 'abababa' -> [0, 2, 4]", () ->
                checkEquals(List.of(0, 2, 4), KMPMatcher.search("abababa", "aba"), "KMP positions"));

        test("KMP returns nothing when the pattern is absent", () ->
                check(KMPMatcher.search("all systems normal", "OutOfMemoryError").isEmpty(), "expected no match"));

        test("KMP finds a signature inside a real log line", () ->
                check(KMPMatcher.contains("ERROR OutOfMemoryError: Java heap space", "OutOfMemoryError"),
                        "signature should be found"));

        test("Rabin-Karp finds several different patterns in ONE pass", () -> {
            String text = "ERROR OutOfMemoryError then Disk full and OutOfMemoryError again";
            Map<String, String> patterns = new LinkedHashMap<>();
            patterns.put("OOM", "OutOfMemoryError");
            patterns.put("DISK", "Disk full");
            patterns.put("AUTH", "Authentication failed");
            Map<String, List<Integer>> hits = RabinKarpMatcher.searchMultiple(text, patterns);
            checkEquals(List.of(text.indexOf("OutOfMemoryError"), text.lastIndexOf("OutOfMemoryError")),
                    hits.get("OOM"), "OOM positions");
            checkEquals(List.of(text.indexOf("Disk full")), hits.get("DISK"), "DISK positions");
            check(hits.get("AUTH").isEmpty(), "AUTH should not match");
        });

        test("Edit distance: kitten -> sitting = 3 (classic textbook example)", () ->
                checkEquals(3, EditDistanceMatcher.distance("kitten", "sitting"), "distance"));

        test("Edit distance: empty string vs 'abc' = 3 (three insertions)", () ->
                checkEquals(3, EditDistanceMatcher.distance("", "abc"), "distance"));

        test("Similarity of identical strings = 1.0", () ->
                check(EditDistanceMatcher.similarity("same", "same") == 1.0, "should be 1.0"));

        test("Near-duplicate error lines (node-7 vs node-9) are >= 0.82 similar", () ->
                check(EditDistanceMatcher.isNearDuplicate(
                        "WARN Cache eviction storm detected on node-7, evicted 5200 keys",
                        "WARN Cache eviction storm detected on node-9, evicted 4890 keys", 0.82),
                        "should be treated as the same recurring error"));

        test("Unrelated lines are NOT near-duplicates", () ->
                check(!EditDistanceMatcher.isNearDuplicate(
                        "WARN Cache eviction storm detected on node-7, evicted 5200 keys",
                        "INFO Health check OK", 0.82),
                        "should be different errors"));

        test("Suffix array of 'banana' = [5, 3, 1, 0, 4, 2] and LCP = [0, 1, 3, 0, 0, 2]", () -> {
            SuffixArrayDiscovery d = new SuffixArrayDiscovery("banana");
            check(Arrays.equals(new int[]{5, 3, 1, 0, 4, 2}, d.getSuffixArray()),
                    "suffix array was " + Arrays.toString(d.getSuffixArray()));
            check(Arrays.equals(new int[]{0, 1, 3, 0, 0, 2}, d.getLcpArray()),
                    "LCP array was " + Arrays.toString(d.getLcpArray()));
        });

        test("Suffix array discovery: longest repeat in 'banana' (len>=3) is 'ana'", () -> {
            List<SuffixArrayDiscovery.RepeatCandidate> r = new SuffixArrayDiscovery("banana").topRepeats(5, 3);
            check(!r.isEmpty(), "expected a candidate");
            checkEquals("ana", r.get(0).substring, "top repeat");
        });

        // -------------------------------------------------------------
        section("CO1 / CO3: scheduling and dynamic programming");
        // -------------------------------------------------------------
        test("Job sequencing (classic textbook case) -> order c, a, e with profit 142", () -> {
            List<RemediationScheduler.Task> tasks = List.of(
                    new RemediationScheduler.Task("a", 100, 2),
                    new RemediationScheduler.Task("b", 19, 1),
                    new RemediationScheduler.Task("c", 27, 2),
                    new RemediationScheduler.Task("d", 25, 1),
                    new RemediationScheduler.Task("e", 15, 3));
            RemediationScheduler.ScheduleResult r = RemediationScheduler.schedule(tasks);
            checkEquals(142, r.totalProfit, "total profit");
            List<String> ids = new ArrayList<>();
            for (RemediationScheduler.Task t : r.scheduledInOrder) {
                ids.add(t.id);
            }
            checkEquals(List.of("c", "a", "e"), ids, "slot order");
        });

        test("Bitmask DP picks the best subset under budget 4 -> {A, C}, cost 3, value 5", () -> {
            ErrorSignature a = new ErrorSignature("A", "aaa", Severity.CRITICAL, 2); // value 4
            ErrorSignature b = new ErrorSignature("B", "bbb", Severity.HIGH, 3);     // value 3
            ErrorSignature c = new ErrorSignature("C", "ccc", Severity.LOW, 1);      // value 1
            BitmaskSignatureSelector.Selection s = BitmaskSignatureSelector.selectBest(List.of(a, b, c), 4);
            checkEquals(5, s.totalValue, "total value");
            checkEquals(3, s.totalCost, "total cost");
            Set<String> ids = new HashSet<>();
            for (ErrorSignature sig : s.chosen) {
                ids.add(sig.getId());
            }
            checkEquals(Set.of("A", "C"), ids, "chosen signatures");
        });

        test("Bitmask DP: nothing fits in a budget of 0 -> empty selection", () -> {
            ErrorSignature a = new ErrorSignature("A", "aaa", Severity.CRITICAL, 2);
            BitmaskSignatureSelector.Selection s = BitmaskSignatureSelector.selectBest(List.of(a), 0);
            check(s.chosen.isEmpty(), "nothing should be chosen");
        });

        test("Interval DP: three quiet buckets [0,0,0] merge into ONE window (cost 10)", () -> {
            IntervalReportMerger m = new IntervalReportMerger(new int[]{0, 0, 0});
            checkEquals(10, m.minimumCost(), "min cost");
            checkEquals(1, m.optimalSegments().size(), "segment count");
        });

        test("Interval DP: two heavy buckets [9,9] stay as TWO windows (cost 38)", () -> {
            IntervalReportMerger m = new IntervalReportMerger(new int[]{9, 9});
            checkEquals(38, m.minimumCost(), "min cost");
            checkEquals(2, m.optimalSegments().size(), "segment count");
        });

        test("Interval DP: [1,1,1,20,20,1,1,1] -> quiet edges merge, the two bursts stand alone", () -> {
            IntervalReportMerger m = new IntervalReportMerger(new int[]{1, 1, 1, 20, 20, 1, 1, 1});
            checkEquals(98, m.minimumCost(), "min cost");
            List<IntervalReportMerger.Segment> segs = m.optimalSegments();
            checkEquals(4, segs.size(), "segment count");
            checkEquals(0, segs.get(0).startBucket, "seg0 start");
            checkEquals(2, segs.get(0).endBucket, "seg0 end");
            checkEquals(3, segs.get(1).startBucket, "seg1 start");
            checkEquals(3, segs.get(1).endBucket, "seg1 end");
            checkEquals(4, segs.get(2).startBucket, "seg2 start");
            checkEquals(5, segs.get(3).startBucket, "seg3 start");
            checkEquals(7, segs.get(3).endBucket, "seg3 end");
        });

        // -------------------------------------------------------------
        section("CO4: network flow");
        // -------------------------------------------------------------
        test("Edmonds-Karp max flow on a 4-node network = 5, and min-cut capacity = 5 (duality)", () -> {
            NetworkFlowAssigner g = new NetworkFlowAssigner(4);
            g.addEdge(0, 1, 3);
            g.addEdge(0, 2, 2);
            g.addEdge(1, 2, 5);
            g.addEdge(1, 3, 2);
            g.addEdge(2, 3, 3);
            int flow = g.maxFlow(0, 3);
            checkEquals(5, flow, "max flow");
            int cutCapacity = 0;
            for (NetworkFlowAssigner.MinCutEdge e : g.minCutEdges(0)) {
                cutCapacity += e.capacity;
            }
            checkEquals(flow, cutCapacity, "min-cut capacity must equal max flow");
        });

        test("Ticket assignment: capacity is enough -> everything assigned, no bottleneck", () -> {
            EngineerAssignment.Result r = EngineerAssignment.assign(
                    List.of("X", "Y"), List.of(3, 2),
                    Map.of("X", "A", "Y", "B"),
                    List.of(new EngineerAssignment.Engineer("E1", 2, Set.of("A")),
                            new EngineerAssignment.Engineer("E2", 5, Set.of("A", "B"))));
            checkEquals(5, r.totalAssigned, "assigned");
            check(r.bottleneckEngineers.isEmpty(), "no engineer should be a bottleneck");
        });

        test("Ticket assignment: 6 tickets but the only skilled engineer has capacity 2 -> 2 assigned, E1 is the bottleneck", () -> {
            EngineerAssignment.Result r = EngineerAssignment.assign(
                    List.of("X"), List.of(6),
                    Map.of("X", "A"),
                    List.of(new EngineerAssignment.Engineer("E1", 2, Set.of("A"))));
            checkEquals(2, r.totalAssigned, "assigned");
            check(r.bottleneckEngineers.size() == 1 && r.bottleneckEngineers.get(0).startsWith("E1"),
                    "bottleneck list was " + r.bottleneckEngineers);
        });

        test("Ticket assignment: nobody has the required skill -> 0 assigned", () -> {
            EngineerAssignment.Result r = EngineerAssignment.assign(
                    List.of("X"), List.of(4),
                    Map.of("X", "A"),
                    List.of(new EngineerAssignment.Engineer("E1", 9, Set.of("B"))));
            checkEquals(0, r.totalAssigned, "assigned");
        });

        // -------------------------------------------------------------
        section("CO5: NP-hard Vertex Cover, 2-approximation");
        // -------------------------------------------------------------
        test("Path graph a-b-c-d: cover is valid and <= 2 x optimal (optimal = 2)", () -> {
            ErrorCorrelationGraph g = new ErrorCorrelationGraph();
            g.addCooccurrence("a", "b");
            g.addCooccurrence("b", "c");
            g.addCooccurrence("c", "d");
            Set<String> cover = VertexCoverApproximation.approximate(g);
            assertValidCover(g, cover);
            check(cover.size() <= 2 * 2, "cover size " + cover.size() + " exceeds 2 x OPT");
        });

        test("Star graph (centre + 4 leaves): cover is valid and <= 2 x optimal (optimal = 1)", () -> {
            ErrorCorrelationGraph g = new ErrorCorrelationGraph();
            for (String leaf : List.of("l1", "l2", "l3", "l4")) {
                g.addCooccurrence("centre", leaf);
            }
            Set<String> cover = VertexCoverApproximation.approximate(g);
            assertValidCover(g, cover);
            check(cover.size() <= 2 * 1, "cover size " + cover.size() + " exceeds 2 x OPT");
        });

        test("Graph with no edges -> empty cover", () ->
                check(VertexCoverApproximation.approximate(new ErrorCorrelationGraph()).isEmpty(),
                        "cover should be empty"));

        // -------------------------------------------------------------
        section("CO6: randomised + parallel algorithms");
        // -------------------------------------------------------------
        test("Las Vegas randomised-select: 3rd smallest of [7,1,5,3,9] is 5 (checked 50 times)", () -> {
            int[] data = {7, 1, 5, 3, 9};
            for (int i = 0; i < 50; i++) {
                checkEquals(5, RandomizedAlgorithms.randomizedSelect(data, 2), "select result (must ALWAYS be exact)");
            }
        });

        test("Median of [4,1,3,2] = 2.5 and median of [9,1,5] = 5.0", () -> {
            check(RandomizedAlgorithms.medianOf(new int[]{4, 1, 3, 2}) == 2.5, "even-length median");
            check(RandomizedAlgorithms.medianOf(new int[]{9, 1, 5}) == 5.0, "odd-length median");
        });

        test("Monte Carlo: all-error log estimates 1.0, all-clean log estimates 0.0", () -> {
            List<String> allBad = Collections.nCopies(200, "ERROR boom");
            List<String> allGood = Collections.nCopies(200, "INFO fine");
            check(RandomizedAlgorithms.monteCarloErrorRateEstimate(allBad, 0.2, s -> s.contains("ERROR")) == 1.0, "all bad");
            check(RandomizedAlgorithms.monteCarloErrorRateEstimate(allGood, 0.2, s -> s.contains("ERROR")) == 0.0, "all good");
        });

        test("Monte Carlo: a 50/50 log is estimated within +/-0.06 of 0.5 (random, but overwhelmingly likely)", () -> {
            List<String> mixed = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                mixed.add("ERROR x");
                mixed.add("INFO y");
            }
            double est = RandomizedAlgorithms.monteCarloErrorRateEstimate(mixed, 2.0, s -> s.contains("ERROR"));
            check(Math.abs(est - 0.5) < 0.06, "estimate was " + est);
        });

        test("Parallel reduce: sum of 1..100 = 5050", () -> {
            int[] v = new int[100];
            for (int i = 0; i < 100; i++) {
                v[i] = i + 1;
            }
            checkEquals(5050L, ParallelPrimitives.parallelReduceSum(v), "sum");
        });

        test("Parallel prefix sum of [1..10] = [1,3,6,10,15,21,28,36,45,55]", () -> {
            int[] v = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
            long[] expected = {1, 3, 6, 10, 15, 21, 28, 36, 45, 55};
            check(Arrays.equals(expected, ParallelPrimitives.parallelPrefixSum(v)),
                    "got " + Arrays.toString(ParallelPrimitives.parallelPrefixSum(v)));
        });

        test("Parallel prefix sum matches a plain sequential loop on 1000 random numbers", () -> {
            Random rnd = new Random(42);
            int[] v = new int[1000];
            for (int i = 0; i < v.length; i++) {
                v[i] = rnd.nextInt(50);
            }
            long[] par = ParallelPrimitives.parallelPrefixSum(v);
            long running = 0;
            for (int i = 0; i < v.length; i++) {
                running += v[i];
                if (par[i] != running) {
                    throw new AssertionError("mismatch at index " + i + ": " + par[i] + " vs " + running);
                }
            }
        });

        // -------------------------------------------------------------
        section("Integration: real log file (skipped if not run from project root)");
        // -------------------------------------------------------------
        Path sample = Paths.get("data/sample_server.log");
        if (Files.exists(sample)) {
            test("sample_server.log: Rabin-Karp counts match the hand-counted totals", () -> {
                Map<String, String> patterns = new LinkedHashMap<>();
                patterns.put("NULL_POINTER", "NullPointerException");
                patterns.put("CONN_REFUSED", "Connection refused");
                patterns.put("TIMEOUT", "Timeout waiting for response");
                patterns.put("AUTH_FAILED", "Authentication failed");
                patterns.put("OOM", "OutOfMemoryError");
                patterns.put("DISK_FULL", "Disk full");
                patterns.put("NOT_FOUND_404", "404 Not Found");
                patterns.put("DEPRECATED_API", "Deprecated API used");

                Map<String, Integer> counts = new LinkedHashMap<>();
                for (String line : Files.readAllLines(sample)) {
                    Map<String, List<Integer>> hits = RabinKarpMatcher.searchMultiple(line, patterns);
                    for (Map.Entry<String, List<Integer>> h : hits.entrySet()) {
                        if (!h.getValue().isEmpty()) {
                            counts.merge(h.getKey(), 1, Integer::sum);
                        }
                    }
                }
                checkEquals(4, counts.get("NULL_POINTER"), "NULL_POINTER");
                checkEquals(4, counts.get("CONN_REFUSED"), "CONN_REFUSED");
                checkEquals(3, counts.get("TIMEOUT"), "TIMEOUT");
                checkEquals(3, counts.get("AUTH_FAILED"), "AUTH_FAILED");
                checkEquals(3, counts.get("OOM"), "OOM");
                checkEquals(3, counts.get("DISK_FULL"), "DISK_FULL");
                checkEquals(3, counts.get("NOT_FOUND_404"), "NOT_FOUND_404");
                checkEquals(2, counts.get("DEPRECATED_API"), "DEPRECATED_API");
            });
        } else {
            System.out.println("  [SKIP] data/sample_server.log not found (run from the logmon project folder)");
        }

        System.out.println("\n------------------------------------------");
        System.out.println("Result: " + passed + " passed, " + failed + " failed");
        System.out.println(failed == 0 ? "ALL TESTS PASSED" : "SOME TESTS FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** Every edge of the graph must have at least one endpoint inside the cover. */
    private static void assertValidCover(ErrorCorrelationGraph g, Set<String> cover) {
        for (String u : g.nodes()) {
            for (String v : g.neighborsOf(u)) {
                check(cover.contains(u) || cover.contains(v),
                        "edge (" + u + ", " + v + ") is not covered by " + cover);
            }
        }
    }
}
