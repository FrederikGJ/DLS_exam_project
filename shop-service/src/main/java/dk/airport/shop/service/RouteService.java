package dk.airport.shop.service;

import dk.airport.shop.domain.*;
import dk.airport.shop.repository.NavEdgeRepository;
import dk.airport.shop.repository.NavNodeRepository;
import dk.airport.shop.repository.ShopRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds step-by-step routes between navigation nodes using {@link Dijkstra}. */
@Service
@Transactional(readOnly = true)
public class RouteService {

    /** Average walking speed used for the time estimate. */
    public static final double METRES_PER_MINUTE = 80.0;

    private final NavNodeRepository nodes;
    private final NavEdgeRepository edges;
    private final ShopRepository shops;

    public RouteService(NavNodeRepository nodes, NavEdgeRepository edges, ShopRepository shops) {
        this.nodes = nodes;
        this.edges = edges;
        this.shops = shops;
    }

    public Route route(Long fromNodeId, Long toNodeId, boolean accessibleOnly) {
        NavNode from = nodes.findById(fromNodeId).orElseThrow(() -> ApiException.notFound("NavNode", fromNodeId));
        NavNode to = nodes.findById(toNodeId).orElseThrow(() -> ApiException.notFound("NavNode", toNodeId));

        if (from.getId().equals(to.getId())) {
            return new Route(List.of(new RouteStep(from, "Du er allerede ved " + from.getName(), 0)), 0, 0,
                    shops.findByNodeIdOrderByName(from.getId()));
        }

        List<NavEdge> allEdges = edges.findAllWithNodes();
        Map<Long, NavNode> nodeById = new HashMap<>();
        List<Dijkstra.Edge> graph = new ArrayList<>(allEdges.size());
        for (NavEdge e : allEdges) {
            nodeById.put(e.getFromNode().getId(), e.getFromNode());
            nodeById.put(e.getToNode().getId(), e.getToNode());
            graph.add(new Dijkstra.Edge(e.getFromNode().getId(), e.getToNode().getId(), e.getDistanceM(),
                    e.isAccessible()));
        }

        Dijkstra.Path path = new Dijkstra(graph).shortestPath(from.getId(), to.getId(), accessibleOnly)
                .orElseThrow(() -> new ApiException(ErrorCode.ROUTE_NOT_FOUND,
                        "No " + (accessibleOnly ? "accessible " : "") + "route from " + from.getName()
                                + " to " + to.getName()));

        List<RouteStep> steps = new ArrayList<>(path.nodeIds().size());
        NavNode previous = null;
        for (int i = 0; i < path.nodeIds().size(); i++) {
            NavNode node = nodeById.getOrDefault(path.nodeIds().get(i), i == 0 ? from : to);
            if (i == 0) {
                steps.add(new RouteStep(node, "Start ved " + node.getName(), 0));
            } else {
                Dijkstra.Edge used = path.edges().get(i - 1);
                boolean last = i == path.nodeIds().size() - 1;
                steps.add(new RouteStep(node, instruction(previous, node, used, last), used.distance()));
            }
            previous = node;
        }

        int total = path.totalDistance();
        int minutes = estimatedMinutes(total);

        Map<Long, List<Shop>> shopsByNode = shops.findByNodeIdIn(path.nodeIds()).stream()
                .collect(Collectors.groupingBy(s -> s.getNode().getId()));
        List<Shop> along = path.nodeIds().stream()
                .flatMap(id -> shopsByNode.getOrDefault(id, List.of()).stream()
                        .sorted(Comparator.comparing(Shop::getName)))
                .toList();

        return new Route(steps, total, minutes, along);
    }

    /**
     * Route with a stop-over: {@code from -> via -> to} as two Dijkstra legs joined into one Route. The via node
     * appears once (its arrival step becomes a "Stop ved ..." instruction, the second leg's start step is dropped),
     * distances are summed, the walking time is recomputed from the total and shops along both legs are listed once.
     */
    public Route routeVia(Long fromNodeId, Long viaNodeId, Long toNodeId, boolean accessibleOnly) {
        Route first = route(fromNodeId, viaNodeId, accessibleOnly);
        if (viaNodeId.equals(toNodeId)) {
            return first;
        }
        Route second = route(viaNodeId, toNodeId, accessibleOnly);

        List<RouteStep> steps = new ArrayList<>(first.steps());
        RouteStep arrival = steps.remove(steps.size() - 1);
        NavNode via = arrival.node();
        NavNode to = second.steps().get(second.steps().size() - 1).node();
        String stopOver = first.steps().size() == 1
                ? "Start ved " + via.getName()
                : "Stop ved " + via.getName() + ", fortsæt derefter mod " + to.getName();
        steps.add(new RouteStep(via, stopOver, arrival.distance()));
        steps.addAll(second.steps().subList(1, second.steps().size()));

        int total = first.totalDistanceM() + second.totalDistanceM();
        Map<Long, Shop> along = new LinkedHashMap<>();
        first.shopsAlongRoute().forEach(s -> along.putIfAbsent(s.getId(), s));
        second.shopsAlongRoute().forEach(s -> along.putIfAbsent(s.getId(), s));
        return new Route(steps, total, estimatedMinutes(total), List.copyOf(along.values()));
    }

    /** Walking time at {@link #METRES_PER_MINUTE}, rounded up; at least one minute for any distance at all. */
    static int estimatedMinutes(int totalMetres) {
        return totalMetres == 0 ? 0 : (int) Math.max(1, Math.ceil(totalMetres / METRES_PER_MINUTE));
    }

    static String instruction(NavNode previous, NavNode node, Dijkstra.Edge used, boolean last) {
        if (last) {
            return "Du er fremme ved " + node.getName();
        }
        boolean floorChanged = !Objects.equals(previous.getFloor(), node.getFloor());
        if (floorChanged) {
            return used.accessible()
                    ? "Tag elevatoren til etage " + node.getFloor()
                    : "Tag trappen til etage " + node.getFloor();
        }
        return "Gå " + used.distance() + " m til " + node.getName();
    }

    /** Small helper used by tests and the controller. */
    public static <T, K> Map<K, T> index(Collection<T> items, Function<T, K> key) {
        return items.stream().collect(Collectors.toMap(key, Function.identity(), (a, b) -> a));
    }
}
