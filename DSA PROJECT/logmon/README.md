# Automated Log Error Monitoring System

A Java implementation of the DSA-3 mini project. It scans server log files,
detects known and unknown recurring error patterns, prioritises them, and
produces a summary report — matching the project abstract and PPT outline.

## How to build and run

Requires JDK 11+ (any recent JDK). No external libraries — pure `java.util` /
`java.nio` / `java.time`.

```bash
cd logmon
# compile
find src -name "*.java" > sources.txt
javac -d out/classes @sources.txt

# run (uses data/sample_server.log by default)
java -cp out/classes logmon.Main

# or point it at your own log file
java -cp out/classes logmon.Main path/to/your.log
```

The program prints a full pipeline trace to the console and also writes
`out/summary_report.txt`.

## Project structure

```
src/main/java/logmon/
  model/       LogEntry, ErrorSignature, ErrorEvent, Severity
  matching/    KMPMatcher, RabinKarpMatcher, EditDistanceMatcher, SuffixArrayDiscovery
  optimize/    BitmaskSignatureSelector, IntervalReportMerger, RemediationScheduler
  flow/        NetworkFlowAssigner, EngineerAssignment (CO4)
  npc/         ErrorCorrelationGraph, VertexCoverApproximation (CO5)
  randomized/  RandomizedAlgorithms (CO6)
  parallel/    ParallelPrimitives (CO6)
  monitor/     LogMonitor (real-time file watching)
  report/      ReportGenerator
  Main.java    orchestrates the full pipeline
data/sample_server.log   sample input log used for the demo run
```

## How this maps to the Course Outcomes

The brief asked for **at least 1–2 topics from each of CO1–CO6**. This
project uses more, so the algorithm-selection story (CO1) is concrete rather
than decorative, and every CO shows up as a real, runnable stage of the
pipeline, not a token example.

| CO | Topic (from the CO table) | Where it's implemented | Why it's the right tool here |
|----|---------------------------|-------------------------|-------------------------------|
| CO1 | Substring search (problem-class recognition) | `matching/KMPMatcher.java`, `matching/RabinKarpMatcher.java` used from `Main` | Looking for a literal, known error signature inside a log line is exactly the "exact substring search" problem class. |
| CO1 | Sequence alignment | `matching/EditDistanceMatcher.java`, used in `Main.clusterUnmatchedByEditDistance` | The *same* real error often shows up as slightly different text (different thread id, host, line number). That's not an exact-match problem — it's a sequence-alignment problem, so it needs edit-distance DP, not KMP/Rabin-Karp. |
| CO1 | NP-hard scheduling | `optimize/RemediationScheduler.java` | Deciding which recurring errors get fixed first under limited "remediation slots" is a scheduling problem. The general weighted-scheduling family is NP-hard, but this project explicitly recognises that the *unit-time, single-machine, deadline+profit* special case (Job Sequencing) has an efficient exact greedy/Union-Find solution — CO1 is precisely about making that recognition instead of blindly reaching for brute force. |
| CO2 | KMP (linear-time string algorithm) | `matching/KMPMatcher.java` | Used for an O(n+m) single-pattern lookup across the whole log corpus (e.g. an admin manually searching one known signature). |
| CO2 | Rabin-Karp with rolling hash | `matching/RabinKarpMatcher.java` | Used as the main detector: one rolling-hash pass scans a line for *all* same-length known signatures at once, which is far cheaper than running KMP once per signature per line. |
| CO2 | Suffix array / LCP array (suffix structures) | `matching/SuffixArrayDiscovery.java` | Built over every line that matched *no* known signature, to surface the longest repeated substrings — i.e. auto-discover brand-new recurring error patterns nobody has written a signature for yet (directly supports the "Future Scope" goal in the PPT). |
| CO3 | Bitmask DP (DP on subsets) | `optimize/BitmaskSignatureSelector.java` | In a resource-constrained monitoring mode, chooses the subset of signatures to actively track that maximises severity-weighted value under a cost budget — solved with an explicit DP-over-subsets recurrence (`dp[mask] = dp[mask ^ lowestBit] + item`), the textbook bitmask-DP pattern. |
| CO3 | Interval DP | `optimize/IntervalReportMerger.java` | Detected errors are bucketed into fixed time windows; interval DP (`dp[i][j] = min(mergeCost(i,j), min over k of dp[i][k]+dp[k+1][j])`) decides how to merge/split those windows into the least noisy set of report segments (every report costs a fixed overhead plus width × volume, so quiet neighbours merge and bursts stay separate) — the same recurrence shape as matrix-chain / optimal-partition problems. |
| CO4 | Network flow (Ford-Fulkerson / Edmonds-Karp) | `flow/NetworkFlowAssigner.java` | Assigning detected error tickets to on-call engineers, each with a daily capacity and a limited skill set, is a capacity-constrained bipartite assignment problem — modelled as `source → error-type demand nodes → engineer supply nodes → sink` and solved with Edmonds-Karp (BFS-based augmenting paths, polynomial regardless of capacity size). |
| CO4 | Max-flow / min-cut duality | `flow/NetworkFlowAssigner.minCutEdges`, used in `flow/EngineerAssignment.java` | After computing max flow, the min cut (found via residual-graph reachability from the source) is extracted and reported as the actual bottleneck engineer(s) whose capacity is fully saturated — a concrete, working demonstration of the duality theorem, not just a definition. |
| CO5 | NP-completeness recognition (P/NP/NP-complete/NP-hard, reductions) | `npc/VertexCoverApproximation.java` (see its Javadoc) | Choosing the smallest set of error types that "covers" every observed correlated-failure pair is exactly Minimum Vertex Cover — the code explains why it's NP-complete via the standard 3-SAT → Independent Set → Vertex Cover reduction chain, instead of silently reaching for brute force. |
| CO5 | Approximation algorithm with provable ratio | `npc/VertexCoverApproximation.java` | Implements the classical maximal-matching-based 2-approximation for Vertex Cover (pick an uncovered edge, take both endpoints, repeat) — the Javadoc walks through why the result is always ≤ 2× the true optimum, and it runs in O(V+E) instead of the O(2ⁿ) exact algorithm. |
| CO6 | Randomised algorithms — Las Vegas | `randomized/RandomizedAlgorithms.randomizedSelect` / `medianOf` | Finds the median error-type frequency using randomised quickselect (random pivot each partition) instead of a full sort — always exactly correct, only the running time is randomised, which is the definition of a Las Vegas algorithm. |
| CO6 | Randomised algorithms — Monte Carlo | `randomized/RandomizedAlgorithms.monteCarloErrorRateEstimate` | Estimates the log's overall error rate from a random sample of lines instead of scanning everything — always finishes in bounded time, but the answer is an approximation with sampling error, the defining Monte Carlo trade-off, contrasted directly against the exact Rabin-Karp/KMP scans used elsewhere. |
| CO6 | Parallel-algorithm primitives | `parallel/ParallelPrimitives.java` | Implements parallel reduce (sum) and parallel prefix sum with Java's Fork/Join framework, with each method's Javadoc giving an explicit **work** (total operations) and **span** (critical-path length) analysis — e.g. parallel reduce is O(n) work / O(log n) span, which is exactly the work-span framing CO6 asks for. |

## Pipeline (what `Main` actually does, in order)

1. Load `data/sample_server.log` into `LogEntry` objects.
2. **KMP** demo: one-shot full-corpus lookup for a single known critical signature.
3. **Rabin-Karp**: scans every line against all 8 predefined signatures in one rolling-hash pass; matched lines become `ErrorEvent`s and bump each signature's frequency counter.
4. **Edit distance**: every line that matched *no* known signature but looks error/warning-like is fuzzy-clustered against previously seen unmatched lines (similarity ≥ 0.82), so "the same" error with different variable text (host name, thread id, key count, ...) is still recognised as one recurring class.
5. **Suffix array + LCP**: run once over all unmatched lines to suggest new candidate signatures (long substrings that repeat often) for a human to review and add to the known list.
6. **Bitmask DP**: shown as a separate "what if we could only afford to monitor half our signatures" resource-constrained scenario.
7. **Interval DP**: buckets every detected event into 5-minute windows, then computes the least-noisy way to merge those windows into report segments.
8. **Job sequencing (CO1 NP-hard-scheduling class)**: turns each detected error type into a remediation task (profit = severity × frequency, deadline by severity) and computes the best schedule that fits the available slots.
9. **Network flow / Edmonds-Karp (CO4)**: builds a bipartite flow network from error types to 4 on-call engineers (each with a daily capacity and a skill set) and computes the maximum number of tickets that can be assigned, plus the min-cut bottleneck engineer(s).
10. **NP-hard Vertex Cover + 2-approximation (CO5)**: builds a graph of error types that co-occurred in the same 5-minute window, then computes a small "core" set of error types guaranteed to be within 2× the smallest possible set that still covers every correlated pair.
11. **Randomised algorithms (CO6)**: computes the median error-type frequency with randomised quickselect (Las Vegas), and estimates the overall error rate from a 40% random sample of lines (Monte Carlo), printed alongside the true rate for comparison.
12. **Parallel primitives (CO6)**: computes the total severity-weighted score with a Fork/Join parallel reduce, and the cumulative bucketed error counts with a Fork/Join parallel prefix sum.
13. **Report**: everything above is written to `out/summary_report.txt` (error type / frequency / severity table, merged report windows, remediation schedule, flow assignment, vertex cover, randomised + parallel results).
14. **Continuous monitoring demo**: copies the sample log to `out/live_demo.log`, starts a `LogMonitor` (Java `WatchService`, tail -f style) on a background thread, appends two new error lines a moment later, and prints them as "LIVE DETECTED" the instant they're picked up — demonstrating the abstract's "continuous monitoring... in real time" requirement.

## Testing and demos

See **`TEST_GUIDE.md`** for a full walkthrough. In short:

| What | How |
|---|---|
| 32 unit tests (every algorithm, hand-checkable inputs) | `./run_tests.sh` (Windows: `run_tests.bat`), or VS Code → Run and Debug → "1) Run unit tests" |
| 4 demo logs in `data/` (mixed errors, critical burst, clean server, unknown errors) | `./run_all_demos.sh`, or pick "2)…5)" in VS Code's Run and Debug dropdown (configs are in `.vscode/launch.json`) |
| What the output should look like | `expected_output/*.txt` (Monte Carlo and live-demo timestamps vary run to run) |

## Extending it

- Add more entries to `Main.buildKnownSignatures()` to grow the signature list.
- Point `Main` at a real server log file: `java -cp out/classes logmon.Main /var/log/myapp.log`.
- To genuinely tail a live production log instead of the bundled demo, call
  `new LogMonitor(path).watch(callback)` directly on your own log path.

  /**
 * Automated Log Error Monitoring System
 * -------------------------------------
 * CO1: substring-search (KMP/Rabin-Karp) vs sequence-alignment (edit
 *      distance) vs NP-hard-scheduling (job sequencing) - three distinct
 *      "problem classes" recognised and each matched with the right
 *      algorithm strategy.
 * CO2: KMP, Rabin-Karp rolling hash (linear-time string algorithms) and a
 *      suffix array + LCP array (suffix-based structure) for pattern
 *      discovery.
 * CO3: bitmask DP (subset selection) and interval DP (report-window
 *      merging) - advanced DP patterns for polynomial-time optimisation.
 * CO4: network flow (Edmonds-Karp) and the max-flow/min-cut duality, used
 *      to assign error tickets to on-call engineers under capacity limits.
 * CO5: recognising an NP-complete problem class (Minimum Vertex Cover, via
 *      the 3-SAT -> Independent Set -> Vertex Cover reduction chain) and
 *      solving it with a provable 2-approximation algorithm.
 * CO6: randomised algorithms (Las Vegas randomised-select, Monte Carlo
 *      sampling) and parallel-algorithm primitives (parallel reduce,
 *      parallel prefix sum) with work-span analysis.
 */
