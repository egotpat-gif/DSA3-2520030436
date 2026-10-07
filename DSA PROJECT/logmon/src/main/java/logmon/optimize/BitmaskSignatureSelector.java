package logmon.optimize;

import logmon.model.ErrorSignature;

import java.util.ArrayList;
import java.util.List;

/**
 * CO3: DP on subsets (bitmask DP).
 *
 * In a resource-constrained deployment (e.g. an embedded agent, or a mode
 * that must keep CPU overhead low) the system may not be able to actively
 * scan for every known signature on every line. Each signature has a
 * monitoring "cost" and a "value" (severity weight x historical frequency).
 * We want the subset of signatures that fits within a monitoring budget and
 * maximises total value - the classic 0/1 knapsack problem, solved here with
 * an explicit DP-over-subsets (bitmask) formulation rather than the usual
 * capacity-indexed array, since the signature count is small (<= ~20) which
 * is exactly where bitmask DP shines and generalises naturally to richer,
 * mask-dependent constraints later (e.g. mutually exclusive signature
 * groups).
 *
 * dp[mask] is built incrementally from dp[mask without its lowest set bit],
 * so every subset's total cost/value is computed in O(1) from an already
 * solved smaller subset: this is the defining trait of bitmask DP.
 */
public final class BitmaskSignatureSelector {

    private BitmaskSignatureSelector() {
    }

    public static class Selection {
        public final List<ErrorSignature> chosen;
        public final int totalCost;
        public final int totalValue;

        Selection(List<ErrorSignature> chosen, int totalCost, int totalValue) {
            this.chosen = chosen;
            this.totalCost = totalCost;
            this.totalValue = totalValue;
        }
    }

    /**
     * @param signatures candidate signatures (size must be small, <= 20 recommended)
     * @param budget     maximum total monitoring cost allowed
     */
    public static Selection selectBest(List<ErrorSignature> signatures, int budget) {
        int n = signatures.size();
        if (n == 0) {
            return new Selection(new ArrayList<>(), 0, 0);
        }
        if (n > 22) {
            throw new IllegalArgumentException("Bitmask DP over subsets is only used here for small n (<=22).");
        }

        int totalMasks = 1 << n;
        int[] costOf = new int[totalMasks];
        int[] valueOf = new int[totalMasks];

        for (int mask = 1; mask < totalMasks; mask++) {
            int lowestBit = mask & (-mask);
            int idx = Integer.numberOfTrailingZeros(lowestBit);
            int subMask = mask ^ lowestBit; // mask without its lowest set bit - already solved
            costOf[mask] = costOf[subMask] + signatures.get(idx).getCost();
            valueOf[mask] = valueOf[subMask] + signatures.get(idx).value();
        }

        int bestMask = 0;
        int bestValue = -1;
        for (int mask = 0; mask < totalMasks; mask++) {
            if (costOf[mask] <= budget && valueOf[mask] > bestValue) {
                bestValue = valueOf[mask];
                bestMask = mask;
            }
        }

        List<ErrorSignature> chosen = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if ((bestMask & (1 << i)) != 0) {
                chosen.add(signatures.get(i));
            }
        }
        return new Selection(chosen, costOf[bestMask], valueOf[bestMask]);
    }
}
