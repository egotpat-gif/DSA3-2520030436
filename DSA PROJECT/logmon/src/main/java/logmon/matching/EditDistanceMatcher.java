package logmon.matching;

/**
 * CO1: Sequence alignment (Levenshtein edit distance) via dynamic programming.
 *
 * Real error log lines that represent "the same" recurring error are rarely
 * byte-identical: a NullPointerException at line 482 today and line 485
 * tomorrow, or a "Connection refused: host db-3" vs "host db-7" are the same
 * underlying problem with a different variable token. Plain substring search
 * (KMP / Rabin-Karp) only recognises EXACT, predefined signatures. To also
 * recognise near-duplicate lines and cluster them as one recurring error
 * class, we align two message strings with the classic edit-distance DP and
 * treat lines within a small distance threshold (relative to length) as the
 * same recurring error.
 *
 * This directly demonstrates the CO1 outcome of recognising a "sequence
 * alignment" problem class and picking the right DP-based algorithm strategy
 * for it, as distinct from the exact-substring problem class handled by KMP
 * and Rabin-Karp.
 */
public final class EditDistanceMatcher {

    private EditDistanceMatcher() {
    }

    /** Classic O(n*m) edit distance DP (insert/delete/substitute, unit cost). */
    public static int distance(String a, String b) {
        int n = a.length();
        int m = b.length();
        int[][] dp = new int[n + 1][m + 1];

        for (int i = 0; i <= n; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= m; j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                } else {
                    int sub = dp[i - 1][j - 1] + 1;
                    int del = dp[i - 1][j] + 1;
                    int ins = dp[i][j - 1] + 1;
                    dp[i][j] = Math.min(sub, Math.min(del, ins));
                }
            }
        }
        return dp[n][m];
    }

    /**
     * Similarity ratio in [0,1], 1.0 meaning identical, based on edit distance
     * normalised by the longer string's length.
     */
    public static double similarity(String a, String b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        int maxLen = Math.max(a.length(), b.length());
        int dist = distance(a, b);
        return 1.0 - ((double) dist / maxLen);
    }

    /** True if two lines are close enough (>= threshold similarity) to be the same recurring error. */
    public static boolean isNearDuplicate(String a, String b, double threshold) {
        return similarity(a, b) >= threshold;
    }
}
