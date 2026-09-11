package dk.airport.shop.service;

import java.util.*;

/**
 * Plain Dijkstra shortest path over an undirected weighted graph.
 * Every edge is treated as bidirectional. When {@code accessibleOnly} is true,
 * edges flagged as not accessible (stairs etc.) are ignored.
 * No Spring dependencies - unit tested in isolation.
 */
public final class Dijkstra {

    /** Input edge. Stored once in the DB; usable in both directions. */
    public record Edge(long from, long to, int distance, boolean accessible) {
        public Edge {
            if (distance <= 0) {
                throw new IllegalArgumentException("distance must be > 0");
            }
        }

        /** The node at the other end of the edge, seen from {@code node}. */
        long other(long node) {
            return node == from ? to : from;
        }
    }

    /** Result path: ordered node ids (from..to), the edges used between them and the total distance. */
    public record Path(List<Long> nodeIds, List<Edge> edges, int totalDistance) {}

    private final Map<Long, List<Edge>> adjacency = new HashMap<>();

    public Dijkstra(Collection<Edge> edges) {
        for (Edge e : edges) {
            adjacency.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
            adjacency.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e);
        }
    }

    public boolean hasNode(long nodeId) {
        return adjacency.containsKey(nodeId);
    }

    public Optional<Path> shortestPath(long from, long to, boolean accessibleOnly) {
        if (from == to) {
            return Optional.of(new Path(List.of(from), List.of(), 0));
        }
        if (!adjacency.containsKey(from) || !adjacency.containsKey(to)) {
            return Optional.empty();
        }

        Map<Long, Integer> dist = new HashMap<>();
        Map<Long, Long> prevNode = new HashMap<>();
        Map<Long, Edge> prevEdge = new HashMap<>();
        Set<Long> settled = new HashSet<>();
        PriorityQueue<long[]> queue = new PriorityQueue<>(Comparator.comparingLong(a -> a[1]));

        dist.put(from, 0);
        queue.add(new long[]{from, 0});

        while (!queue.isEmpty()) {
            long[] current = queue.poll();
            long node = current[0];
            if (!settled.add(node)) {
                continue;               // stale queue entry
            }
            if (node == to) {
                break;
            }
            int nodeDist = dist.get(node);
            for (Edge e : adjacency.getOrDefault(node, List.of())) {
                if (accessibleOnly && !e.accessible()) {
                    continue;
                }
                long neighbour = e.other(node);
                if (settled.contains(neighbour)) {
                    continue;
                }
                int candidate = nodeDist + e.distance();
                if (candidate < dist.getOrDefault(neighbour, Integer.MAX_VALUE)) {
                    dist.put(neighbour, candidate);
                    prevNode.put(neighbour, node);
                    prevEdge.put(neighbour, e);
                    queue.add(new long[]{neighbour, candidate});
                }
            }
        }

        if (!dist.containsKey(to)) {
            return Optional.empty();
        }

        LinkedList<Long> nodes = new LinkedList<>();
        LinkedList<Edge> edges = new LinkedList<>();
        long cursor = to;
        nodes.addFirst(cursor);
        while (cursor != from) {
            edges.addFirst(prevEdge.get(cursor));
            cursor = prevNode.get(cursor);
            nodes.addFirst(cursor);
        }
        return Optional.of(new Path(List.copyOf(nodes), List.copyOf(edges), dist.get(to)));
    }
}
