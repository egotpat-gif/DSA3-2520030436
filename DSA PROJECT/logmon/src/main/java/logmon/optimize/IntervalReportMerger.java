package logmon.optimize;

import java.util.ArrayList;
import java.util.List;

/**
 * CO3: Interval DP.
 *
 * Detected errors are bucketed into fixed time windows (e.g. one bucket per
 * hour of the log). Reporting every single bucket separately can flood the
 * admin with noise; merging everything into one giant window loses when the
 * bursts actually happened. We want the partition of the bucket sequence
 * into contiguous report segments that minimises a total "reporting cost" -
 * a textbook interval DP: dp[i][j] represents the best cost of presenting
 * buckets i..j either as a single merged segment or as an optimal split into
 * two contiguous sub-segments, exactly the recurrence shape used for matrix
 * chain multiplication / optimal partitioning problems.
 */
public final class IntervalReportMerger {

    /** Fixed cost of producing ONE report segment (headers, admin attention, ...).
     *  Without this, splitting into single buckets would always be free and the DP
     *  would never merge anything; with it, merging quiet adjacent buckets saves
     *  overhead while merging busy ones costs precision. */
    private static final int SEGMENT_OVERHEAD = 10;

    private final int[] bucketCounts;
    private final int n;
    private final int[][] dp;
    private final int[][] splitPoint; // -1 => this range is kept as one merged segment

    public IntervalReportMerger(int[] bucketCounts) {
        this.bucketCounts = bucketCounts;
        this.n = bucketCounts.length;
        this.dp = new int[n][n];
        this.splitPoint = new int[n][n];
        solve();
    }

    /** Cost of presenting buckets [i, j] as a single report segment:
     *  a fixed overhead plus (span x total error volume). The second term grows
     *  with both width and volume, so wide high-volume bursts are worth breaking
     *  into more precise sub-segments, while the overhead makes it worth merging
     *  quiet neighbouring buckets. For a single bucket this is overhead + count. */
    private int mergeCost(int i, int j) {
        int span = j - i + 1;
        int sum = 0;
        for (int k = i; k <= j; k++) {
            sum += bucketCounts[k];
        }
        return SEGMENT_OVERHEAD + span * sum;
    }

    private void solve() {
        for (int i = 0; i < n; i++) {
            dp[i][i] = mergeCost(i, i); // a single bucket still costs one report segment
            splitPoint[i][i] = -1;
        }
        for (int len = 2; len <= n; len++) {
            for (int i = 0; i + len - 1 < n; i++) {
                int j = i + len - 1;
                // Option A: keep [i,j] as one merged segment.
                dp[i][j] = mergeCost(i, j);
                splitPoint[i][j] = -1;
                // Option B: split into [i,k] and [k+1,j] for every k, keep the best.
                for (int k = i; k < j; k++) {
                    int candidate = dp[i][k] + dp[k + 1][j];
                    if (candidate < dp[i][j]) {
                        dp[i][j] = candidate;
                        splitPoint[i][j] = k;
                    }
                }
            }
        }
    }

    public static class Segment {
        public final int startBucket;
        public final int endBucket;
        public final int totalCount;

        Segment(int startBucket, int endBucket, int totalCount) {
            this.startBucket = startBucket;
            this.endBucket = endBucket;
            this.totalCount = totalCount;
        }
    }

    /** The minimum total reporting cost for the whole bucket sequence. */
    public int minimumCost() {
        return n == 0 ? 0 : dp[0][n - 1];
    }

    /** Reconstructs the optimal list of merged report segments. */
    public List<Segment> optimalSegments() {
        List<Segment> segments = new ArrayList<>();
        if (n > 0) {
            reconstruct(0, n - 1, segments);
        }
        return segments;
    }

    private void reconstruct(int i, int j, List<Segment> out) {
        if (splitPoint[i][j] == -1) {
            int sum = 0;
            for (int k = i; k <= j; k++) {
                sum += bucketCounts[k];
            }
            out.add(new Segment(i, j, sum));
        } else {
            int k = splitPoint[i][j];
            reconstruct(i, k, out);
            reconstruct(k + 1, j, out);
        }
    }
}
