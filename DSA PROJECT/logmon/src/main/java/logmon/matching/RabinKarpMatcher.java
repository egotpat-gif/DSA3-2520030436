package logmon.matching;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CO2: Rabin-Karp pattern search using a rolling polynomial hash.
 *
 * A production log line typically has to be checked against MANY signatures
 * at once. Re-running KMP once per signature is wasteful. Rabin-Karp's rolling
 * hash lets us group same-length signatures and slide a single window over
 * the text, computing each window's hash in O(1) from the previous one, and
 * only doing an O(len) character-by-character confirmation when a hash
 * collides with one of the target hashes. This gives near O(n) scanning for
 * a whole bundle of same-length patterns per pass.
 */
public final class RabinKarpMatcher {

    private static final long BASE = 256L;
    private static final long MOD = 1_000_000_007L;

    private RabinKarpMatcher() {
    }

    private static long hashOf(String s, int from, int len) {
        long h = 0;
        for (int i = 0; i < len; i++) {
            h = (h * BASE + s.charAt(from + i)) % MOD;
        }
        return h;
    }

    private static long power(long base, long exp, long mod) {
        long result = 1;
        base %= mod;
        while (exp > 0) {
            if ((exp & 1) == 1) {
                result = (result * base) % mod;
            }
            base = (base * base) % mod;
            exp >>= 1;
        }
        return result;
    }

    /**
     * Searches {@code text} for every occurrence of any pattern in
     * {@code patterns}, all handled in one rolling-hash pass per distinct
     * pattern length.
     *
     * @return map of patternId -> list of starting indices found in text
     */
    public static Map<String, List<Integer>> searchMultiple(String text, Map<String, String> patterns) {
        Map<String, List<Integer>> results = new HashMap<>();
        for (String id : patterns.keySet()) {
            results.put(id, new ArrayList<>());
        }

        // Group patterns by length so we can slide one window size at a time.
        Map<Integer, List<Map.Entry<String, String>>> byLength = new HashMap<>();
        for (Map.Entry<String, String> e : patterns.entrySet()) {
            int len = e.getValue().length();
            if (len == 0 || len > text.length()) {
                continue;
            }
            byLength.computeIfAbsent(len, k -> new ArrayList<>()).add(e);
        }

        for (Map.Entry<Integer, List<Map.Entry<String, String>>> group : byLength.entrySet()) {
            int m = group.getKey();
            List<Map.Entry<String, String>> groupPatterns = group.getValue();

            // Pre-compute target hashes for every pattern of this length.
            Map<Long, List<Map.Entry<String, String>>> targetHashes = new HashMap<>();
            for (Map.Entry<String, String> e : groupPatterns) {
                long h = hashOf(e.getValue(), 0, m);
                targetHashes.computeIfAbsent(h, k -> new ArrayList<>()).add(e);
            }

            long highOrder = power(BASE, m - 1, MOD); // BASE^(m-1) mod MOD, used to drop the leading char
            long windowHash = hashOf(text, 0, m);

            for (int i = 0; i + m <= text.length(); i++) {
                if (i > 0) {
                    // Roll the hash: drop leftmost char, add new rightmost char.
                    windowHash = (windowHash - (text.charAt(i - 1) * highOrder) % MOD + MOD) % MOD;
                    windowHash = (windowHash * BASE + text.charAt(i + m - 1)) % MOD;
                }
                List<Map.Entry<String, String>> candidates = targetHashes.get(windowHash);
                if (candidates != null) {
                    for (Map.Entry<String, String> cand : candidates) {
                        // Hash collision is possible (mod arithmetic) - verify with a direct compare.
                        if (text.regionMatches(i, cand.getValue(), 0, m)) {
                            results.get(cand.getKey()).add(i);
                        }
                    }
                }
            }
        }
        return results;
    }
}
