package logmon.npc;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * CO5 (support structure): an undirected graph where nodes are error-type
 * ids and an edge (u, v) means those two error types were observed
 * co-occurring in the same time bucket at least once - i.e. they tend to
 * fail together (e.g. a DB connection failure and a timeout minutes apart
 * during the same outage). This graph is the input to the NP-hard Vertex
 * Cover problem solved in {@link VertexCoverApproximation}.
 */
public class ErrorCorrelationGraph {

    private final Map<String, Set<String>> adjacency = new LinkedHashMap<>();

    public void addCooccurrence(String a, String b) {
        if (a.equals(b)) {
            return;
        }
        adjacency.computeIfAbsent(a, k -> new LinkedHashSet<>()).add(b);
        adjacency.computeIfAbsent(b, k -> new LinkedHashSet<>()).add(a);
    }

    public Set<String> nodes() {
        return adjacency.keySet();
    }

    public Set<String> neighborsOf(String node) {
        return adjacency.getOrDefault(node, java.util.Collections.emptySet());
    }

    /** Deep copy of the adjacency map, so callers (e.g. the approximation algorithm) can mutate freely. */
    public Map<String, Set<String>> copyAdjacency() {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : adjacency.entrySet()) {
            copy.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
        }
        return copy;
    }

    public int edgeCount() {
        int sum = 0;
        for (Set<String> neighbors : adjacency.values()) {
            sum += neighbors.size();
        }
        return sum / 2;
    }
}
