package logmon.npc;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * CO5: Recognising an NP-hard problem class and applying a provable-ratio
 * approximation algorithm to it.
 *
 * We want the SMALLEST set of error types such that every "correlated
 * failure pair" (an edge in {@link ErrorCorrelationGraph}) has at least one
 * of its two error types in that set - i.e. a minimum set of dashboards/
 * alerts that is guaranteed to catch every known failure correlation. This
 * is exactly the MINIMUM VERTEX COVER problem.
 *
 * Vertex Cover is one of Karp's original 21 NP-complete problems: it is in
 * NP (a candidate cover of size k is trivially checkable in polynomial
 * time), and it is NP-hard via the standard textbook reduction chain
 * 3-SAT -> Independent Set -> Vertex Cover (a set S is a vertex cover of G
 * iff V \ S is an independent set of G, so minimum vertex cover and maximum
 * independent set are complementary problems over the same NP-hardness
 * proof). Finding the EXACT minimum therefore has no known polynomial-time
 * algorithm, and brute force is O(2^n).
 *
 * Because exact solving does not scale, we instead recognise this as a
 * problem class that admits a well-known, PROVABLY BOUNDED approximation:
 * repeatedly take any still-uncovered edge (u, v), add BOTH endpoints to the
 * cover, and discard every edge touching u or v (since both endpoints are
 * now covered, none of their incident edges can be violated). The set of
 * edges picked this way forms a matching (no two picked edges share a
 * vertex), so if the optimal cover has size OPT, it must contain at least
 * one endpoint from each of those matched edges - meaning OPT >= (number of
 * edges picked). Our cover has size exactly 2 x (number of edges picked), so
 *
 *      |our cover| <= 2 * OPT
 *
 * This is the classical 2-approximation for Vertex Cover, and it runs in
 * O(V + E) - polynomial, unlike the exact exponential solution.
 */
public final class VertexCoverApproximation {

    private VertexCoverApproximation() {
    }

    public static Set<String> approximate(ErrorCorrelationGraph graph) {
        Map<String, Set<String>> adjacency = graph.copyAdjacency();
        Set<String> cover = new LinkedHashSet<>();

        for (String u : adjacency.keySet()) {
            // Keep picking edges until this node has none left (its edges may have
            // already been removed by an earlier pick involving one of its neighbors).
            while (!adjacency.get(u).isEmpty()) {
                String v = adjacency.get(u).iterator().next();

                cover.add(u);
                cover.add(v);

                removeAllEdgesOf(adjacency, u);
                removeAllEdgesOf(adjacency, v);
            }
        }
        return cover;
    }

    private static void removeAllEdgesOf(Map<String, Set<String>> adjacency, String node) {
        Set<String> neighbors = adjacency.get(node);
        if (neighbors == null) {
            return;
        }
        for (String neighbor : neighbors) {
            Set<String> back = adjacency.get(neighbor);
            if (back != null) {
                back.remove(node);
            }
        }
        neighbors.clear();
    }
}
