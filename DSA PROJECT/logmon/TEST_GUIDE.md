# Test Guide – how to demo the project and explain the output

> **Honesty note.** The "expected output" below was produced by replaying the
> *same algorithms and the same print formats* in a separate Python script
> (my build environment has no JDK, so I could not run the Java itself).
> Everything deterministic should match your screen exactly. The two things
> that legitimately differ run-to-run are marked **(varies)**. If anything else
> differs, that is a real finding – tell me and I will fix it.
> Full predicted outputs are saved in the `expected_output/` folder so you can
> compare side by side.

---

## 1. How to run things

### In VS Code (easiest)
1. Open the `logmon` folder (File → Open Folder).
2. Click the **Run and Debug** icon on the left (▶ with a bug), or press `Ctrl+Shift+D`.
3. At the top, use the dropdown to pick one of the 5 ready-made configurations
   (they live in `.vscode/launch.json`) and press the green ▶:

| Dropdown entry | What it runs |
|---|---|
| 1) Run unit tests | 32 small tests, one line PASS/FAIL each |
| 2) Demo: sample_server.log | The main demo (mixed errors) |
| 3) Demo: test2_critical_burst.log | A burst of critical errors |
| 4) Demo: test3_clean_server.log | A healthy server (no errors) |
| 5) Demo: test4_unknown_errors.log | Errors that are NOT on the known list |

### From a terminal
```bash
./run_tests.sh                                  # unit tests   (Windows: run_tests.bat)
./run_all_demos.sh                              # all 4 demos  (Windows: run_demo.bat data\test2_critical_burst.log)
java -cp out/classes logmon.Main data/test4_unknown_errors.log   # one demo, after building
```

---

## 2. Unit tests (start your demo here – 1 minute)

Run configuration **1**. Expected result (abridged – full list in `expected_output/unit_tests.txt`):

```
== CO1 / CO2: string matching ==
  [PASS] KMP finds overlapping matches: 'aba' in 'abababa' -> [0, 2, 4]
  ...
== CO4: network flow ==
  [PASS] Edmonds-Karp max flow on a 4-node network = 5, and min-cut capacity = 5 (duality)
  ...
Result: 32 passed, 0 failed
ALL TESTS PASSED
```

**How to explain it:** "Every test gives an algorithm a tiny input whose answer we can work out
by hand, and checks the program agrees. For example `kitten → sitting` needs exactly 3 edits, the
suffix array of `banana` is `[5,3,1,0,4,2]`, and the textbook job-sequencing example must give
profit 142. Two tests are worth pointing out:"

* **Max-flow = min-cut test** – computes the max flow (5) *and* separately adds up the capacity of
  the min-cut edges (also 5). Equal numbers = the max-flow/min-cut theorem verified in code.
* **Las Vegas test** – runs randomised-select 50 times; it must return the exact right answer *every*
  time (only the running time is random). The Monte-Carlo tests, by contrast, allow a tolerance,
  because a Monte-Carlo answer is only approximate by design.

---

## 3. Demo 1 – `data/sample_server.log` (the main demo)

32 log lines: 3 harmless INFO lines, 25 lines matching the 8 known error signatures, and
4 "Cache eviction storm" warnings that are **not** on the known list.

### Expected console output (Monte-Carlo line **varies**)
```
Loaded 32 log lines from data/sample_server.log

[CO2 | KMP] Full-corpus lookup for "OutOfMemoryError": 3 occurrence(s) at character offsets [497, 1034, 1558]

[CO1 | Edit-Distance] Formed 1 fuzzy cluster(s) from 7 unmatched line(s) (similarity threshold 0.82):
   FUZZY-CLUSTER-0 x4  e.g. "WARN Cache eviction storm detected on node-7, evicted 5200 keys"

[CO2 | Suffix Array + LCP] Candidate NEW error signatures discovered from previously unmatched lines:
   "WARN Cache eviction storm detected on node-7, evicted 5" (len=55)
   "INFO Health check OK" (len=20)

[CO3 | Bitmask DP] Resource-constrained monitoring mode (budget=11 of max 23):
   Selected signatures (total cost=11, total value=62):
     NULL_POINTER[NullPointerException] severity=HIGH freq=4 cost=3
     CONN_REFUSED[Connection refused] severity=HIGH freq=4 cost=4
     OOM[OutOfMemoryError] severity=CRITICAL freq=3 cost=2
     DISK_FULL[Disk full] severity=CRITICAL freq=3 cost=2

[CO3 | Interval DP] 10 time-buckets (each 5 min) merged into 5 report window(s), min total cost=108

[CO1 | Job-Sequencing / NP-hard-scheduling class] 4 of 9 candidate remediation tasks fit into the available slots (total profit=35)

[CO4 | Network Flow / Edmonds-Karp] Ticket assignment: 11 of 29 error tickets assignable within today's engineer capacities.
     Asha (backend) -> 3 ticket(s) assigned
     Ravi (infra) -> 2 ticket(s) assigned
     Kiran (security) -> 2 ticket(s) assigned
     Priya (on-call/misc) -> 4 ticket(s) assigned
   Min-cut bottleneck(s) (max-flow/min-cut duality - capacity fully used): [Asha (backend) (capacity 3), Ravi (infra) (capacity 2), Kiran (security) (capacity 2), Priya (on-call/misc) (capacity 4)]

[CO5 | NP-hard recognition + 2-approx Vertex Cover] Correlation graph has 9 error-type node(s) and 21 correlation edge(s).
   Minimal-effort monitoring core set (guaranteed <= 2x optimal size): [NULL_POINTER, CONN_REFUSED, DEPRECATED_API, TIMEOUT, AUTH_FAILED, OOM, FUZZY-CLUSTER-0, DISK_FULL]

[CO6 | Randomised Algorithms]
   Las Vegas (randomised-select): median error-type frequency = 3.0 (always exact, expected O(n) time)
   Monte Carlo (40% random sample): estimated error-line rate = 0.692 (actual = 0.719, sampling error = 0.026)   <-- (varies)

[CO6 | Parallel Primitives] parallel-reduce total severity-weighted score = 76 (work O(n), span O(log n))
   parallel-prefix-sum of bucketed error counts (cumulative, work O(n log n), span O(log^2 n)): [2, 5, 9, 13, 16, 19, 22, 25, 28, 29]
```
followed by the printed summary report (identical content is saved to `out/summary_report.txt`) and
the live-monitoring demo.

### Line-by-line explanation

| Output | What it means and why it comes out that way |
|---|---|
| `Loaded 32 log lines` | The file has 32 non-blank lines. |
| **KMP** – `3 occurrence(s)` | KMP scanned the whole log as one big string for `OutOfMemoryError` and found it 3 times. The numbers are *character offsets* in that big string (not line numbers). Linear time: it never re-reads characters. |
| **Edit-Distance** – `1 fuzzy cluster … x4` | 7 lines matched no known signature: 3 INFO lines and 4 "Cache eviction storm" warnings. INFO lines are ignored (not error/warn). The 4 warnings differ only in the node number and key count (e.g. `node-7` vs `node-9`), so their edit-distance similarity to the first one is 0.94–0.98, above the 0.82 threshold → **one recurring problem seen 4 times**, not 4 unrelated ones. This is why exact matching (KMP/Rabin-Karp) is the wrong tool here: it's the *sequence alignment* problem class. |
| **Suffix Array + LCP** – 2 candidates | Run over the 7 unmatched lines, it finds text that repeats. The first candidate is the cache-eviction message, i.e. **a new signature we should add to the known list**. The second (`INFO Health check OK`) is harmless – a false positive. That's the honest point to make: the tool *suggests*, a human *decides*. |
| **Bitmask DP** – budget 11 of 23 | Pretend we can only afford half the total monitoring cost (23 → 11). Each signature's value = severity weight × (frequency + 1); e.g. OOM = 4 × (3+1) = 16. The DP tries every subset and returns the best: the four signatures above, cost 3+4+2+2 = 11, value 15+15+16+16 = 62. `TIMEOUT` is left out: cost 5 for value only 8. |
| **Interval DP** – 10 buckets → 5 windows, cost 108 | The 46-minute log is cut into ten 5-minute buckets with error counts `[2,3,4,4,3,3,3,3,3,1]` (their running total is the parallel-prefix line at the bottom). Every report costs 10 overhead + (width × errors). Merging two neighbours is cheaper than two separate reports (e.g. buckets 2 and 3: 10+2×8 = 26 vs 2×(10+4) = 28), but merging four is not, so the DP settles on 5 pairs. |
| **Job-Sequencing** – 4 of 9, profit 35 | Each error type becomes a fix-it task: profit = severity × count, deadline = slot 1 (critical) … slot 4 (low). There are only 4 slots, so the best schedule is OOM (12) → NULL_POINTER (12) → FUZZY-CLUSTER-0 (8) → NOT_FOUND_404 (3) = 35. `DISK_FULL` (also critical, profit 12) loses because both critical tasks need slot 1 and OOM came first – a real limitation of the model worth acknowledging. |
| **Network Flow** – 11 of 29 | 29 error tickets, but the four engineers can only take 3+2+2+4 = **11** in a day, so 18 remain. Edmonds-Karp finds this maximum by repeatedly pushing flow along the *shortest* source→sink path (BFS). |
| **Min-cut bottleneck** – all four engineers | The min cut is the set of edges whose removal disconnects source from sink; here it's exactly the four engineer→sink capacity edges (3+2+2+4 = 11 = the max flow). That's the max-flow/min-cut theorem in action, and it tells management *which* resource limits throughput: everyone is saturated → hire more people. |
| **Vertex Cover** – 9 nodes, 21 edges, cover of 8 | Two error types get an edge if they occurred in the same 5-minute window. Finding the *smallest* set covering every edge is Vertex Cover – NP-complete, so we use the 2-approximation (take both ends of an uncovered edge, repeat). Guarantee: our 8 is at most twice the true minimum. The graph is dense (nearly everything co-occurs), so nearly every type ends up in the set; on a sparser log the cover is much smaller. |
| **Las Vegas** – median 3.0 | The 9 error-type counts are `2,3,3,3,3,3,4,4,4`; the median is 3. Randomised quickselect gets it without fully sorting, and the answer is *always* exact. |
| **Monte Carlo** **(varies)** | Draws round(32 × 0.4) = 13 random lines and reports the fraction containing `ERROR`. True rate is 23/32 = 0.719, so with 13 draws you'll typically see values like 0.615, 0.692, 0.769 … and it changes every run. That variation *is* the lesson: fast, bounded time, approximate answer. |
| **Parallel Primitives** – score 76, prefix sums | *Parallel reduce* adds up the severity weight (4/3/2/1) of all 29 events = 76. *Parallel prefix sum* turns the bucket counts into running totals; the last value, **29**, equals the total number of events – a handy consistency check. Both use Java Fork/Join (split in halves, compute in parallel, combine). |

### The report and the live demo
* The **summary report** lists error types sorted by severity then count: OOM 3, DISK_FULL 3 (CRITICAL);
  NULL_POINTER 4, CONN_REFUSED 4, AUTH_FAILED 3 (HIGH); FUZZY-CLUSTER-0 4, TIMEOUT 3 (MEDIUM);
  NOT_FOUND_404 3, DEPRECATED_API 2 (LOW), followed by the merged windows, the schedule, and CO4–CO6 sections.
  Full text: `expected_output/test1_sample_server.txt`.
* **Live monitoring**: the program copies the log, starts watching it, appends two new error lines and prints
  ```
  [LIVE DETECTED] NULL_POINTER (HIGH) in line: <current time> ERROR NullPointerException at com.app.LiveDemo.run(LiveDemo.java:9)
  [LIVE DETECTED] OOM (CRITICAL) in line: <current time> ERROR OutOfMemoryError: Java heap space in worker-thread-9
  ```
  **(varies)**: the timestamps are "now". On Windows/Linux this fires within a fraction of a second. On
  macOS Java's file watcher can lag by up to ~10 s, so the lines might not appear before the 3-second demo
  ends – if so, run it again or mention that limitation.

---

## 4. Demo 2 – `data/test2_critical_burst.log` ("what if the servers are on fire?")

12 lines in 10 minutes: 4× OutOfMemoryError, 3× Disk full, 2× Connection refused, 1× Timeout.

Key expected lines:
```
[CO3 | Interval DP] 2 time-buckets (each 5 min) merged into 1 report window(s), min total cost=30
[CO1 | Job-Sequencing …] 3 of 4 candidate remediation tasks fit into the available slots (total profit=24)
[CO4 | Network Flow …] Ticket assignment: 7 of 10 error tickets assignable within today's engineer capacities.
     Asha (backend) -> 1 ticket(s) assigned
     Ravi (infra) -> 2 ticket(s) assigned
     Kiran (security) -> 0 ticket(s) assigned
     Priya (on-call/misc) -> 4 ticket(s) assigned
   Min-cut bottleneck(s) …: [Ravi (infra) (capacity 2), Priya (on-call/misc) (capacity 4)]
[CO5 …] Correlation graph has 4 error-type node(s) and 6 correlation edge(s).
```
**What to say:** *The story here is the bottleneck.* Nine of the ten tickets are infrastructure problems
(OOM 4 + disk 3 + connection 2), but only Ravi and Priya are skilled in infra – so only 2 + 4 = 6 of them
can be handled, plus Asha takes the single timeout ticket = 7. Kiran gets nothing (there are no security
errors), and Asha is *not* in the bottleneck list because she's only using 1 of her 3 slots. The min cut
points precisely at Ravi and Priya: "add infra capacity, not more backend engineers."
The other lines: the 2 buckets have counts `[7, 3]`; merged costs 10+2×10 = 30, separate costs 17+13 = 30 – a tie,
and the DP keeps them merged. All 4 error types occur together, so the correlation graph is the complete
graph K4 (6 edges), and the schedule drops `DISK_FULL` because OOM (profit 16) outranks it for the only
slot before the critical deadline.

---

## 5. Demo 3 – `data/test3_clean_server.log` ("does it cope with nothing wrong?")

5 INFO lines, no errors. Key expected lines:
```
[CO1 | Edit-Distance] Formed 0 fuzzy cluster(s) from 5 unmatched line(s) (similarity threshold 0.82):
[CO2 | Suffix Array + LCP] …   "INFO Health check OK" (len=20)
[CO3 | Interval DP] 1 time-buckets (each 5 min) merged into 1 report window(s), min total cost=10
[CO1 | Job-Sequencing …] 0 of 0 candidate remediation tasks fit … (total profit=0)
[CO4 | Network Flow …] Ticket assignment: 0 of 0 error tickets assignable …
   No engineer capacity is fully saturated (no bottleneck in the min cut).
[CO5 …] Correlation graph has 0 error-type node(s) and 0 correlation edge(s).
   Minimal-effort monitoring core set …: []
   Las Vegas … median error-type frequency = 0.0
   Monte Carlo … estimated error-line rate = 0.000 (actual = 0.000 …)
```
**What to say:** "Edge-case test: a healthy server produces an empty report and nothing crashes – empty
flow network, empty graph, empty schedule." The Monte Carlo estimate is exactly 0 every time (no line can
be an error). The suffix array still lists "INFO Health check OK" because it does repeat – again a harmless
suggestion, showing why discovery output needs human review.

---

## 6. Demo 4 – `data/test4_unknown_errors.log` ("errors nobody wrote a rule for")

10 lines: 1 known `NullPointerException`; 3 Kafka-lag errors and 3 SSL-expiry warnings that vary in their numbers/hosts; 1 one-off "Gremlin" error.

Key expected lines:
```
[CO1 | Edit-Distance] Formed 3 fuzzy cluster(s) from 9 unmatched line(s) (similarity threshold 0.82):
   FUZZY-CLUSTER-0 x3  e.g. "ERROR Kafka consumer lag exceeded threshold on partition 3, lag=9120"
   FUZZY-CLUSTER-1 x3  e.g. "WARN SSL certificate expires in 12 days for host api-1"
   FUZZY-CLUSTER-2 x1  e.g. "ERROR Gremlin appeared in module zeta"
[CO2 | Suffix Array + LCP] …
   "ERROR Kafka consumer lag exceeded threshold on partition " (len=57)
   "WARN SSL certificate expires in 12 days for host api-" (len=53)
[CO4 | Network Flow …] Ticket assignment: 5 of 8 error tickets assignable …
   Min-cut bottleneck(s) …: [Priya (on-call/misc) (capacity 4)]
```
**What to say:** *This is the "unknown unknowns" demo.* None of the Kafka/SSL/Gremlin messages match a known
signature, yet the program still (a) groups the variants together – the three Kafka lines are 0.93–0.94 similar
to each other, the SSL lines 0.96–0.98, while Kafka vs SSL is only 0.25 – and (b) via the suffix array suggests
the two repeating templates as new signatures to add. The Gremlin line appears only once, so it forms a
cluster of one and is *not* suggested as a signature. Flow: unknown clusters fall in the "misc" category, which
only Priya covers (cap 4) → she's the bottleneck. (`Monte Carlo` here targets lines containing `ERROR`;
5 of 10 → actual rate 0.500, WARN lines are not counted.)

---

## 7. Suggested 5-minute demo script

1. **Unit tests** (30 s) – "32 checks, all passing, each verifiable by hand."
2. **Demo 1** (2 min) – walk down the CO1→CO6 tags; stress edit-distance clustering, the bottleneck
   from min-cut, and the Las Vegas vs Monte Carlo contrast (run it twice – the Monte Carlo line changes, nothing else does).
3. **Demo 4** (1 min) – discovery of unknown errors.
4. **Demo 3** (30 s) – robustness on a clean log.
5. **Live monitoring** (30 s) – the last lines of any demo run.

## 8. Changes made while preparing these tests
Working out expected outputs by hand exposed two real weaknesses, both now fixed:
* **Interval DP (CO3)** previously charged nothing for a single bucket, so splitting was always free and it
  *never merged anything* (10 separate windows at cost 0). It now includes a fixed per-report overhead, giving the DP a genuine merge-vs-split trade-off.
* **Suffix-array discovery (CO2)** previously printed five near-identical candidates (the same phrase shifted by one
  character each). It now cuts candidates at line boundaries and keeps only maximal, distinct repeats.
