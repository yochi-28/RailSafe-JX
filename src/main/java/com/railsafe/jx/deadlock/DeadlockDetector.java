package com.railsafe.jx.deadlock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** DFS-based cycle detector for a snapshot of the directed wait-for graph. */
public final class DeadlockDetector {
    public List<DeadlockCycle> findCycles(Map<String, Set<String>> graph) {
        if (graph == null || graph.isEmpty()) return List.of();
        Map<String, Integer> color = new HashMap<>(); // 0=white, 1=gray, 2=black
        Deque<String> path = new ArrayDeque<>();
        Map<String, Integer> position = new HashMap<>();
        Map<String, DeadlockCycle> cycles = new LinkedHashMap<>();
        Set<String> nodes = new LinkedHashSet<>(graph.keySet());
        graph.values().forEach(nodes::addAll);
        for (String node : nodes) color.put(node, 0);
        for (String node : nodes) {
            if (color.getOrDefault(node, 0) == 0) dfs(node, graph, color, path, position, cycles);
        }
        return List.copyOf(cycles.values());
    }

    private void dfs(String node, Map<String, Set<String>> graph, Map<String, Integer> color,
                     Deque<String> path, Map<String, Integer> position,
                     Map<String, DeadlockCycle> cycles) {
        color.put(node, 1);
        position.put(node, path.size());
        path.addLast(node);
        for (String next : graph.getOrDefault(node, Set.of())) {
            int nextColor = color.getOrDefault(next, 0);
            if (nextColor == 0) {
                dfs(next, graph, color, path, position, cycles);
            } else if (nextColor == 1) {
                List<String> stack = new ArrayList<>(path);
                int start = position.getOrDefault(next, 0);
                if (start >= 0 && start < stack.size()) {
                    List<String> cycleNodes = new ArrayList<>(stack.subList(start, stack.size()));
                    if (cycleNodes.size() >= 2) {
                        DeadlockCycle cycle = new DeadlockCycle(cycleNodes);
                        cycles.putIfAbsent(cycle.signature(), cycle);
                    }
                }
            }
        }
        path.removeLast();
        position.remove(node);
        color.put(node, 2);
    }
}
