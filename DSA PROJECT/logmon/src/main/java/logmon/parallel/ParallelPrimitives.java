package logmon.parallel;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.RecursiveTask;

/**
 * CO6: Parallel-algorithm primitives - parallel reduce and parallel prefix
 * sum (scan) - implemented with Java's Fork/Join framework, which is the
 * standard way to express divide-and-conquer, work-stealing parallelism on
 * a multi-core machine.
 *
 * Both primitives are analysed the way the course asks: in terms of WORK
 * (total operations across all processors) and SPAN (length of the longest
 * chain of sequentially-dependent operations, i.e. critical path length).
 * A lower span than the equivalent sequential algorithm's total work is
 * what lets more processors actually speed the computation up.
 */
public final class ParallelPrimitives {

    /** Below this segment size we stop forking and just run sequentially -
     *  forking has overhead, so very small sub-problems aren't worth splitting. */
    private static final int SEQUENTIAL_THRESHOLD = 4;

    private ParallelPrimitives() {
    }

    // -----------------------------------------------------------------
    // Parallel reduce (sum)
    // -----------------------------------------------------------------

    /**
     * Parallel reduce: sums an array by recursively splitting it in half,
     * summing each half (potentially on a different thread), and combining.
     * Work = O(n) (same total additions as the sequential version). Span =
     * O(log n) (the recursion tree has depth log n, and combining two
     * results is O(1)), versus O(n) span for a naive sequential loop -
     * this is what allows a parallel speed-up on multi-core hardware.
     */
    public static long parallelReduceSum(int[] values) {
        if (values.length == 0) {
            return 0;
        }
        ForkJoinPool pool = new ForkJoinPool();
        try {
            return pool.invoke(new SumTask(values, 0, values.length));
        } finally {
            pool.shutdown();
        }
    }

    private static class SumTask extends RecursiveTask<Long> {
        private final int[] arr;
        private final int lo;
        private final int hi; // exclusive

        SumTask(int[] arr, int lo, int hi) {
            this.arr = arr;
            this.lo = lo;
            this.hi = hi;
        }

        @Override
        protected Long compute() {
            if (hi - lo <= SEQUENTIAL_THRESHOLD) {
                long sum = 0;
                for (int i = lo; i < hi; i++) {
                    sum += arr[i];
                }
                return sum;
            }
            int mid = (lo + hi) >>> 1;
            SumTask left = new SumTask(arr, lo, mid);
            SumTask right = new SumTask(arr, mid, hi);
            left.fork();                 // left half runs asynchronously (possibly on another thread)
            long rightResult = right.compute(); // this thread does the right half itself
            long leftResult = left.join();       // wait for the forked half to finish
            return leftResult + rightResult;
        }
    }

    // -----------------------------------------------------------------
    // Parallel prefix sum (inclusive scan)
    // -----------------------------------------------------------------

    /**
     * Parallel prefix sum: result[i] = values[0] + values[1] + ... + values[i].
     * Computed with the classic two-phase divide-and-conquer scan: each half
     * is (a) summed to get its total and (b) filled with its own local
     * prefix sums, in parallel; then the left half's total is added onto
     * every element of the right half's local prefix sums.
     *
     * This simple version does O(n log n) total work (each element gets
     * touched by the "add left total" step once per level of recursion it
     * belongs to) with O(log^2 n) span. The optimal Blelloch work-efficient
     * scan (an up-sweep followed by a down-sweep over an implicit balanced
     * tree) achieves O(n) work and O(log n) span; it is more involved and
     * is noted here as the natural next optimisation rather than implemented,
     * to keep this primitive readable.
     */
    public static long[] parallelPrefixSum(int[] values) {
        long[] result = new long[values.length];
        if (values.length == 0) {
            return result;
        }
        ForkJoinPool pool = new ForkJoinPool();
        try {
            pool.invoke(new PrefixTask(values, result, 0, values.length));
        } finally {
            pool.shutdown();
        }
        return result;
    }

    /** Fills result[lo..hi) with LOCAL prefix sums of that segment, and returns the segment's total. */
    private static class PrefixTask extends RecursiveTask<Long> {
        private final int[] src;
        private final long[] dst;
        private final int lo;
        private final int hi; // exclusive

        PrefixTask(int[] src, long[] dst, int lo, int hi) {
            this.src = src;
            this.dst = dst;
            this.lo = lo;
            this.hi = hi;
        }

        @Override
        protected Long compute() {
            if (hi - lo <= SEQUENTIAL_THRESHOLD) {
                long running = 0;
                for (int i = lo; i < hi; i++) {
                    running += src[i];
                    dst[i] = running;
                }
                return running;
            }
            int mid = (lo + hi) >>> 1;
            PrefixTask left = new PrefixTask(src, dst, lo, mid);
            PrefixTask right = new PrefixTask(src, dst, mid, hi);
            left.fork();
            long rightTotal = right.compute();
            long leftTotal = left.join();

            new AddConstant(dst, mid, hi, leftTotal).compute();
            return leftTotal + rightTotal;
        }
    }

    /** Adds a constant offset to every element of dst[lo..hi), also expressed as a fork/join task. */
    private static class AddConstant extends RecursiveAction {
        private final long[] dst;
        private final int lo;
        private final int hi;
        private final long offset;

        AddConstant(long[] dst, int lo, int hi, long offset) {
            this.dst = dst;
            this.lo = lo;
            this.hi = hi;
            this.offset = offset;
        }

        @Override
        protected void compute() {
            if (hi - lo <= SEQUENTIAL_THRESHOLD) {
                for (int i = lo; i < hi; i++) {
                    dst[i] += offset;
                }
                return;
            }
            int mid = (lo + hi) >>> 1;
            AddConstant left = new AddConstant(dst, lo, mid, offset);
            AddConstant right = new AddConstant(dst, mid, hi, offset);
            invokeAll(left, right);
        }
    }
}
