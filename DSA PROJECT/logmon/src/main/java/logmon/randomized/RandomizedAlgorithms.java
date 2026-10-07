package logmon.randomized;

import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

/**
 * CO6: Randomised algorithms - Las Vegas and Monte Carlo.
 *
 * A Las Vegas algorithm always produces the CORRECT answer, but its running
 * time is a random variable. A Monte Carlo algorithm always runs in bounded
 * time, but its answer may occasionally be wrong (or, here, approximate)
 * with some controllable probability/error margin. This module contains one
 * of each, applied to the log-monitoring problem.
 */
public final class RandomizedAlgorithms {

    private static final Random RNG = new Random();

    private RandomizedAlgorithms() {
    }

    // -----------------------------------------------------------------
    // LAS VEGAS: randomised quickselect
    // -----------------------------------------------------------------

    /**
     * Finds the k-th smallest element (0-indexed) of {@code values} using
     * randomised quickselect: a random pivot is chosen at every partition
     * step, giving O(n) EXPECTED time (O(n^2) worst case, but that worst
     * case becomes vanishingly unlikely because the pivot is random rather
     * than fixed). The answer returned is always exactly correct - only the
     * running time is randomised - which is what makes this a Las Vegas
     * algorithm, as opposed to a Monte Carlo one.
     *
     * Used instead of a full O(n log n) sort when we only need one summary
     * statistic (e.g. the median error frequency) out of a list of
     * detected-error counts.
     */
    public static int randomizedSelect(int[] values, int k) {
        int[] copy = values.clone();
        return select(copy, 0, copy.length - 1, k);
    }

    private static int select(int[] a, int lo, int hi, int k) {
        if (lo == hi) {
            return a[lo];
        }
        int pivotIndex = lo + RNG.nextInt(hi - lo + 1);
        int finalPivotIndex = partition(a, lo, hi, pivotIndex);
        if (k == finalPivotIndex) {
            return a[k];
        } else if (k < finalPivotIndex) {
            return select(a, lo, finalPivotIndex - 1, k);
        } else {
            return select(a, finalPivotIndex + 1, hi, k);
        }
    }

    private static int partition(int[] a, int lo, int hi, int pivotIndex) {
        int pivotValue = a[pivotIndex];
        swap(a, pivotIndex, hi);
        int storeIndex = lo;
        for (int i = lo; i < hi; i++) {
            if (a[i] < pivotValue) {
                swap(a, i, storeIndex);
                storeIndex++;
            }
        }
        swap(a, storeIndex, hi);
        return storeIndex;
    }

    private static void swap(int[] a, int i, int j) {
        int tmp = a[i];
        a[i] = a[j];
        a[j] = tmp;
    }

    /** Median of a set of integer values, computed via two randomised-select calls instead of a full sort. */
    public static double medianOf(int[] values) {
        if (values.length == 0) {
            return 0;
        }
        int mid = values.length / 2;
        int upper = randomizedSelect(values, mid);
        if (values.length % 2 == 1) {
            return upper;
        }
        int lower = randomizedSelect(values, mid - 1);
        return (lower + upper) / 2.0;
    }

    // -----------------------------------------------------------------
    // MONTE CARLO: sampling-based error-rate estimator
    // -----------------------------------------------------------------

    /**
     * On a very large log file, scanning EVERY line to get an exact error
     * count may be too slow for a quick health check. This Monte Carlo
     * algorithm instead draws a random sample of lines, measures what
     * fraction look like errors, and reports that fraction as an ESTIMATE of
     * the true error rate. It always finishes in bounded time
     * (O(sampleSize), independent of file size) but the answer carries some
     * sampling error - the defining trade-off of a Monte Carlo algorithm,
     * versus the Rabin-Karp/KMP exact scan used elsewhere in this project.
     */
    public static double monteCarloErrorRateEstimate(List<String> lines, double sampleFraction,
                                                       Predicate<String> looksLikeError) {
        int n = lines.size();
        if (n == 0) {
            return 0.0;
        }
        int sampleSize = Math.max(1, (int) Math.round(n * sampleFraction));
        int hits = 0;
        for (int i = 0; i < sampleSize; i++) {
            String line = lines.get(RNG.nextInt(n));
            if (looksLikeError.test(line)) {
                hits++;
            }
        }
        return (double) hits / sampleSize;
    }
}
