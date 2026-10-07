package logmon.matching;

import java.util.ArrayList;
import java.util.List;

/**
 * CO2: Knuth-Morris-Pratt linear time (O(n + m)) exact substring search.
 *
 * Used to scan a single log line for a single, known error signature without
 * the O(n*m) blow-up of naive substring search. This is the workhorse for
 * "exact, single pattern" lookups, e.g. checking whether a line contains the
 * literal signature "OutOfMemoryError".
 */
public final class KMPMatcher {

    private KMPMatcher() {
    }

    /** Builds the KMP failure/partial-match table for the given pattern. */
    private static int[] buildFailureTable(String pattern) {
        int[] fail = new int[pattern.length()];
        int len = 0;
        int i = 1;
        while (i < pattern.length()) {
            if (pattern.charAt(i) == pattern.charAt(len)) {
                len++;
                fail[i] = len;
                i++;
            } else if (len > 0) {
                len = fail[len - 1];
            } else {
                fail[i] = 0;
                i++;
            }
        }
        return fail;
    }

    /**
     * Returns every starting index in {@code text} at which {@code pattern}
     * occurs, in O(n + m) time.
     */
    public static List<Integer> search(String text, String pattern) {
        List<Integer> matches = new ArrayList<>();
        if (pattern.isEmpty() || text.length() < pattern.length()) {
            return matches;
        }
        int[] fail = buildFailureTable(pattern);
        int i = 0; // index into text
        int j = 0; // index into pattern
        while (i < text.length()) {
            if (text.charAt(i) == pattern.charAt(j)) {
                i++;
                j++;
                if (j == pattern.length()) {
                    matches.add(i - j);
                    j = fail[j - 1];
                }
            } else if (j > 0) {
                j = fail[j - 1];
            } else {
                i++;
            }
        }
        return matches;
    }

    /** Convenience: does the pattern occur at least once? */
    public static boolean contains(String text, String pattern) {
        return !search(text, pattern).isEmpty();
    }
}
