package logmon.flow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * CO4: Network flow (Ford-Fulkerson, Edmonds-Karp variant) and the
 * max-flow / min-cut duality.
 *
 * Once errors are detected, someone has to actually fix them, and on-call
 * engineers have limited daily capacity and only work on categories they're
 * skilled in. "Assign as many error tickets as possible to engineers,
 * respecting per-engineer capacity and skill compatibility" is exactly the
 * capacity-constrained bipartite assignment problem CO4 covers, modelled as
 * max-flow on:
 *
 *   source -> [error-type demand nodes] -> [engineer supply nodes] -> sink
 *
 * with edge capacities = ticket counts (source side) and engineer daily
 * capacity (sink side), and an unlimited edge between a demand node and a
 * supply node only where that engineer is skilled for that error category.
 *
 * We use Edmonds-Karp (Ford-Fulkerson where the augmenting path is always
 * found by BFS, i.e. the shortest augmenting path) which runs in O(V * E^2)
 * and, unlike plain Ford-Fulkerson with arbitrary path choice, is guaranteed
 * polynomial regardless of capacity values.
 *
 * After computing max flow, we also compute the min cut: by the max-flow /
 * min-cut theorem, the maximum flow value always equals the minimum total
 * capacity of edges that, if removed, disconnect source from sink. We find
 * it by taking the set of nodes still reachable from the source in the
 * residual graph - any original edge crossing from a reachable to an
 * unreachable node is a min-cut edge, and those are exactly the bottlenecks
 * (e.g. "Engineer X's capacity is fully saturated") preventing more tickets
 * from being assigned.
 */
public class NetworkFlowAssigner {

    private final int nodeCount;
    private final List<List<Integer>> adjacency; // node -> list of edge indices
    private final List<Integer> edgeTo = new ArrayList<>();
    private final List<Integer> edgeCap = new ArrayList<>();
    private final List<Integer> edgeOriginalCap = new ArrayList<>();
    private final List<Boolean> edgeIsForward = new ArrayList<>();

    public NetworkFlowAssigner(int nodeCount) {
        this.nodeCount = nodeCount;
        this.adjacency = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            adjacency.add(new ArrayList<>());
        }
    }

    /** Adds a directed edge u->v with the given capacity, plus its 0-capacity reverse edge. */
    public void addEdge(int u, int v, int capacity) {
        adjacency.get(u).add(edgeTo.size());
        edgeTo.add(v);
        edgeCap.add(capacity);
        edgeOriginalCap.add(capacity);
        edgeIsForward.add(true);

        adjacency.get(v).add(edgeTo.size());
        edgeTo.add(u);
        edgeCap.add(0);
        edgeOriginalCap.add(0);
        edgeIsForward.add(false);
    }

    /** Finds a shortest (fewest-edges) augmenting path from source to sink via BFS. */
    private int[] bfsAugmentingPath(int source, int sink) {
        int[] parentEdge = new int[nodeCount];
        boolean[] visited = new boolean[nodeCount];
        java.util.Arrays.fill(parentEdge, -1);
        visited[source] = true;

        Queue<Integer> queue = new ArrayDeque<>();
        queue.add(source);
        while (!queue.isEmpty()) {
            int u = queue.poll();
            if (u == sink) {
                break;
            }
            for (int edgeIdx : adjacency.get(u)) {
                int v = edgeTo.get(edgeIdx);
                if (!visited[v] && edgeCap.get(edgeIdx) > 0) {
                    visited[v] = true;
                    parentEdge[v] = edgeIdx;
                    queue.add(v);
                }
            }
        }
        return visited[sink] ? parentEdge : null;
    }

    /** Runs Edmonds-Karp and returns the maximum flow value from source to sink. */
    public int maxFlow(int source, int sink) {
        int totalFlow = 0;
        int[] parentEdge;
        while ((parentEdge = bfsAugmentingPath(source, sink)) != null) {
            // Find the bottleneck capacity along the path.
            int bottleneck = Integer.MAX_VALUE;
            int v = sink;
            while (v != source) {
                int edgeIdx = parentEdge[v];
                bottleneck = Math.min(bottleneck, edgeCap.get(edgeIdx));
                v = edgeTo.get(edgeIdx ^ 1); // the "from" node of this edge is the reverse edge's "to"
            }
            // Push flow along the path.
            v = sink;
            while (v != source) {
                int edgeIdx = parentEdge[v];
                edgeCap.set(edgeIdx, edgeCap.get(edgeIdx) - bottleneck);
                edgeCap.set(edgeIdx ^ 1, edgeCap.get(edgeIdx ^ 1) + bottleneck);
                v = edgeTo.get(edgeIdx ^ 1);
            }
            totalFlow += bottleneck;
        }
        return totalFlow;
    }

    /** Flow actually sent along edge u->v (0 if no such edge was added), read AFTER maxFlow() has run. */
    public int flowOn(int u, int v) {
        for (int edgeIdx : adjacency.get(u)) {
            if (edgeIsForward.get(edgeIdx) && edgeTo.get(edgeIdx) == v) {
                return edgeOriginalCap.get(edgeIdx) - edgeCap.get(edgeIdx);
            }
        }
        return 0;
    }

    public static class MinCutEdge {
        public final int from;
        public final int to;
        public final int capacity;

        MinCutEdge(int from, int to, int capacity) {
            this.from = from;
            this.to = to;
            this.capacity = capacity;
        }
    }

    /**
     * Must be called AFTER maxFlow(). Returns the min-cut edges: every
     * original forward edge whose "from" node is still reachable from the
     * source in the residual graph but whose "to" node is not. Their total
     * capacity always equals the max-flow value (max-flow / min-cut duality).
     */
    public List<MinCutEdge> minCutEdges(int source) {
        boolean[] reachable = new boolean[nodeCount];
        Queue<Integer> queue = new ArrayDeque<>();
        reachable[source] = true;
        queue.add(source);
        while (!queue.isEmpty()) {
            int u = queue.poll();
            for (int edgeIdx : adjacency.get(u)) {
                int v = edgeTo.get(edgeIdx);
                if (!reachable[v] && edgeCap.get(edgeIdx) > 0) {
                    reachable[v] = true;
                    queue.add(v);
                }
            }
        }

        List<MinCutEdge> cut = new ArrayList<>();
        for (int u = 0; u < nodeCount; u++) {
            if (!reachable[u]) {
                continue;
            }
            for (int edgeIdx : adjacency.get(u)) {
                if (!edgeIsForward.get(edgeIdx)) {
                    continue;
                }
                int v = edgeTo.get(edgeIdx);
                if (!reachable[v]) {
                    cut.add(new MinCutEdge(u, v, edgeOriginalCap.get(edgeIdx)));
                }
            }
        }
        return cut;
    }
}
