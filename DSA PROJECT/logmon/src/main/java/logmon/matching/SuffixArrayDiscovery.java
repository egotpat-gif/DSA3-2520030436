package logmon.matching;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CO2: Suffix array + LCP array (suffix-based structures).
 *
 * The predefined signature list (matched via KMP/Rabin-Karp) can only catch
 * errors the team already knows about. To satisfy the "automatic detection of
 * previously unknown error patterns" goal (see Future Scope), we build a
 * suffix array over the text of all UNMATCHED log lines (those that did not
 * hit any known signature) and use Kasai's algorithm to build the LCP
 * (longest common prefix) array. The suffixes with the largest LCP values
 * point at the longest substrings that repeat across many unmatched lines -
 * i.e. good candidates for brand-new error signatures the team should add.
 */
public final class SuffixArrayDiscovery {

    private final String text;
    private final int[] suffixArray;
    private final int[] lcpArray;

    public SuffixArrayDiscovery(String text) {
        this.text = text;
        this.suffixArray = buildSuffixArray(text);
        this.lcpArray = buildLcpArray(text, suffixArray);
    }

    /**
     * Builds the suffix array using the classic O(n log^2 n) prefix-doubling
     * rank-sort algorithm: at each round we know each suffix's rank based on
     * the first 2^k characters, then sort using (rank[i], rank[i + 2^k]) as a
     * comparison key, doubling k each round.
     */
    private static int[] buildSuffixArray(String s) {
        int n = s.length();
        Integer[] sa = new Integer[n];
        int[] rank = new int[n];
        int[] tmp = new int[n];

        for (int i = 0; i < n; i++) {
            sa[i] = i;
            rank[i] = s.charAt(i);
        }

        for (int k = 1; k < n; k <<= 1) {
            final int kk = k;
            final int[] r = rank;
            java.util.Comparator<Integer> cmp = (a, b) -> {
                if (r[a] != r[b]) {
                    return Integer.compare(r[a], r[b]);
                }
                int ra = a + kk < n ? r[a + kk] : -1;
                int rb = b + kk < n ? r[b + kk] : -1;
                return Integer.compare(ra, rb);
            };
            Arrays.sort(sa, cmp);

            tmp[sa[0]] = 0;
            for (int i = 1; i < n; i++) {
                tmp[sa[i]] = tmp[sa[i - 1]] + (cmp.compare(sa[i - 1], sa[i]) < 0 ? 1 : 0);
            }
            rank = Arrays.copyOf(tmp, n);

            if (rank[sa[n - 1]] == n - 1) {
                break; // all suffixes already uniquely ranked
            }
        }

        int[] result = new int[n];
        for (int i = 0; i < n; i++) {
            result[i] = sa[i];
        }
        return result;
    }

    /** Kasai's O(n) algorithm to build the LCP array from the suffix array. */
    private static int[] buildLcpArray(String s, int[] sa) {
        int n = s.length();
        int[] rank = new int[n];
        int[] lcp = new int[n];
        for (int i = 0; i < n; i++) {
            rank[sa[i]] = i;
        }
        int h = 0;
        for (int i = 0; i < n; i++) {
            if (rank[i] > 0) {
                int j = sa[rank[i] - 1];
                while (i + h < n && j + h < n && s.charAt(i + h) == s.charAt(j + h)) {
                    h++;
                }
                lcp[rank[i]] = h;
                if (h > 0) {
                    h--;
                }
            } else {
                h = 0;
            }
        }
        return lcp;
    }

    /** A candidate repeated substring together with how many suffix-array neighbours share it. */
    public static class RepeatCandidate {
        public final String substring;
        public final int lcpLength;
        public final int position;

        RepeatCandidate(String substring, int lcpLength, int position) {
            this.substring = substring;
            this.lcpLength = lcpLength;
            this.position = position;
        }

        @Override
        public String toString() {
            return "\"" + substring + "\" (len=" + lcpLength + ")";
        }
    }

    /**
     * Returns up to {@code topN} of the longest repeated substrings (length >=
     * minLength) found in the corpus - the most likely new error-signature
     * candidates.
     *
     * Two clean-ups keep the output readable:
     *  1. A repeat is cut at the line-separator character (\u0001) so a
     *     candidate never spans two log lines.
     *  2. A repeat that is merely a piece of an already-accepted longer repeat
     *     (e.g. "ARN Cache evict..." inside "WARN Cache evict...") is skipped,
     *     because every shifted suffix of a long repeat also shows up in the LCP
     *     array and would otherwise crowd out genuinely different patterns.
     */
    public List<RepeatCandidate> topRepeats(int topN, int minLength) {
        List<RepeatCandidate> candidates = new ArrayList<>();
        for (int i = 1; i < lcpArray.length; i++) {
            int len = lcpArray[i];
            if (len >= minLength) {
                int pos = suffixArray[i];
                String raw = text.substring(pos, pos + len);
                // keep the longest piece that does not cross a line separator
                String best = "";
                for (String piece : raw.split("\u0001")) {
                    if (piece.length() > best.length()) {
                        best = piece;
                    }
                }
                if (best.length() >= minLength) {
                    candidates.add(new RepeatCandidate(best, best.length(), pos));
                }
            }
        }
        candidates.sort((a, b) -> Integer.compare(b.lcpLength, a.lcpLength));

        List<RepeatCandidate> accepted = new ArrayList<>();
        for (RepeatCandidate c : candidates) {
            boolean alreadyCovered = false;
            for (RepeatCandidate a : accepted) {
                if (a.substring.contains(c.substring)) {
                    alreadyCovered = true;
                    break;
                }
            }
            if (!alreadyCovered) {
                accepted.add(c);
                if (accepted.size() >= topN) {
                    break;
                }
            }
        }
        return accepted;
    }

    public int[] getSuffixArray() {
        return suffixArray;
    }

    public int[] getLcpArray() {
        return lcpArray;
    }
}
