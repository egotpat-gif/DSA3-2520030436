package logmon.optimize;

import java.util.ArrayList;
import java.util.List;

/**
 * CO1: Recognising and solving a scheduling problem class.
 *
 * Once errors are detected and prioritised, the team has limited remediation
 * "slots" (e.g. one fix per hour before the next release freeze) and must
 * decide which recurring errors to fix and in what order to maximise the
 * total severity-weighted benefit. General weighted job scheduling with
 * precedence constraints and multiple resources is NP-hard, and a project at
 * this level would typically reach for a greedy or DP heuristic without
 * first asking "which special case of the scheduling problem is this,
 * exactly?" - that recognition is the point of this module.
 *
 * Here every remediation task takes exactly one unit slot and has a
 * deadline (must be scheduled at or before that slot) and a profit
 * (severity weight x frequency). This specific structure - unit-time,
 * single machine, deadline + profit - is the classical "Job Sequencing
 * Problem", which (unlike the general NP-hard scheduling family) admits an
 * efficient EXACT greedy solution: sort tasks by profit, and greedily place
 * each task in the latest still-free slot at or before its deadline, found
 * efficiently with a Union-Find (disjoint set) structure instead of a naive
 * O(n * maxDeadline) scan.
 */
public final class RemediationScheduler {

    private RemediationScheduler() {
    }

    public static class Task {
        public final String id;
        public final int profit;
        public final int deadline; // must be scheduled in slot 1..deadline

        public Task(String id, int profit, int deadline) {
            this.id = id;
            this.profit = profit;
            this.deadline = deadline;
        }
    }

    public static class ScheduleResult {
        public final List<Task> scheduledInOrder; // slot 1..k, in order
        public final int totalProfit;

        ScheduleResult(List<Task> scheduledInOrder, int totalProfit) {
            this.scheduledInOrder = scheduledInOrder;
            this.totalProfit = totalProfit;
        }
    }

    /** Simple path-compressing disjoint-set used to find the latest free slot <= deadline in near O(1). */
    private static class DisjointSet {
        private final int[] parent;

        DisjointSet(int size) {
            parent = new int[size + 1];
            for (int i = 0; i <= size; i++) {
                parent[i] = i;
            }
        }

        int find(int x) {
            if (parent[x] != x) {
                parent[x] = find(parent[x]);
            }
            return parent[x];
        }

        void union(int x) {
            // Point x to x-1, i.e. mark slot x as used and merge with the slot before it.
            parent[x] = find(x - 1);
        }
    }

    public static ScheduleResult schedule(List<Task> tasks) {
        List<Task> sorted = new ArrayList<>(tasks);
        sorted.sort((a, b) -> Integer.compare(b.profit, a.profit));

        int maxDeadline = 0;
        for (Task t : sorted) {
            maxDeadline = Math.max(maxDeadline, t.deadline);
        }

        Task[] slots = new Task[maxDeadline + 1]; // 1-indexed slots
        DisjointSet dsu = new DisjointSet(maxDeadline);

        int totalProfit = 0;
        for (Task t : sorted) {
            int deadline = Math.min(t.deadline, maxDeadline);
            int availableSlot = dsu.find(deadline);
            if (availableSlot > 0) {
                slots[availableSlot] = t;
                dsu.union(availableSlot);
                totalProfit += t.profit;
            }
            // else: no free slot at or before this task's deadline - it is skipped,
            // exactly as it would be dropped from an optimal schedule.
        }

        List<Task> ordered = new ArrayList<>();
        for (int i = 1; i <= maxDeadline; i++) {
            if (slots[i] != null) {
                ordered.add(slots[i]);
            }
        }
        return new ScheduleResult(ordered, totalProfit);
    }
}
