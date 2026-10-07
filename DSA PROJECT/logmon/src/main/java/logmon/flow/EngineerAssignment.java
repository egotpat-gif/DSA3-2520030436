package logmon.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CO4: builds the concrete bipartite "which error type goes to which
 * on-call engineer" flow network on top of {@link NetworkFlowAssigner} and
 * turns the result back into plain assignment counts and bottleneck
 * explanations.
 *
 * Node layout: 0 = source, 1..D = error-type demand nodes (in the order of
 * {@code demandIds}), D+1..D+S = engineer supply nodes (in the order of
 * {@code engineers}), D+S+1 = sink.
 */
public final class EngineerAssignment {

    public static class Engineer {
        public final String id;
        public final int dailyCapacity;
        public final java.util.Set<String> skilledCategories;

        public Engineer(String id, int dailyCapacity, java.util.Set<String> skilledCategories) {
            this.id = id;
            this.dailyCapacity = dailyCapacity;
            this.skilledCategories = skilledCategories;
        }
    }

    public static class Result {
        public final int totalAssigned;
        public final int totalDemand;
        public final Map<String, Integer> assignedPerEngineer;
        public final List<String> bottleneckEngineers; // engineers whose capacity is the min-cut

        Result(int totalAssigned, int totalDemand, Map<String, Integer> assignedPerEngineer,
               List<String> bottleneckEngineers) {
            this.totalAssigned = totalAssigned;
            this.totalDemand = totalDemand;
            this.assignedPerEngineer = assignedPerEngineer;
            this.bottleneckEngineers = bottleneckEngineers;
        }
    }

    private EngineerAssignment() {
    }

    /**
     * @param demandIds   error-type/category ids needing remediation
     * @param demandCount ticket count for each id (same order as demandIds)
     * @param categoryOf  maps a demandId to the broad category an engineer's skill set is defined over
     * @param engineers   available on-call engineers
     */
    public static Result assign(List<String> demandIds, List<Integer> demandCount,
                                 Map<String, String> categoryOf, List<Engineer> engineers) {
        int d = demandIds.size();
        int s = engineers.size();
        int source = 0;
        int sinkNode = d + s + 1;
        NetworkFlowAssigner flow = new NetworkFlowAssigner(d + s + 2);

        for (int i = 0; i < d; i++) {
            flow.addEdge(source, i + 1, demandCount.get(i));
        }
        for (int j = 0; j < s; j++) {
            flow.addEdge(d + 1 + j, sinkNode, engineers.get(j).dailyCapacity);
        }
        for (int i = 0; i < d; i++) {
            String category = categoryOf.getOrDefault(demandIds.get(i), "MISC");
            for (int j = 0; j < s; j++) {
                if (engineers.get(j).skilledCategories.contains(category)) {
                    flow.addEdge(i + 1, d + 1 + j, Integer.MAX_VALUE / 4); // effectively unlimited
                }
            }
        }

        int maxFlowValue = flow.maxFlow(source, sinkNode);

        Map<String, Integer> assignedPerEngineer = new java.util.LinkedHashMap<>();
        for (int j = 0; j < s; j++) {
            int assigned = flow.flowOn(d + 1 + j, sinkNode);
            assignedPerEngineer.put(engineers.get(j).id, assigned);
        }

        List<NetworkFlowAssigner.MinCutEdge> cut = flow.minCutEdges(source);
        List<String> bottlenecks = new ArrayList<>();
        for (NetworkFlowAssigner.MinCutEdge e : cut) {
            // A cut edge landing exactly on the sink is a saturated engineer-capacity edge.
            if (e.to == sinkNode) {
                int engineerIdx = e.from - d - 1;
                bottlenecks.add(engineers.get(engineerIdx).id + " (capacity " + e.capacity + ")");
            }
        }

        int totalDemand = demandCount.stream().mapToInt(Integer::intValue).sum();
        return new Result(maxFlowValue, totalDemand, assignedPerEngineer, bottlenecks);
    }
}
