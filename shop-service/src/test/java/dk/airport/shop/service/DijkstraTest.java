package dk.airport.shop.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DijkstraTest {

    /*
     *   1 --100-- 2 --10-- 3 --10-- 4        direct 2->4 = 200 (long shortcut)
     *   3 --5(stairs, not accessible)-- 5
     *   3 --40(elevator)-- 6 --40-- 5
     *   7 (isolated)
     */
    private final Dijkstra graph = new Dijkstra(List.of(
            new Dijkstra.Edge(1, 2, 100, true),
            new Dijkstra.Edge(2, 3, 10, true),
            new Dijkstra.Edge(3, 4, 10, true),
            new Dijkstra.Edge(2, 4, 200, true),
            new Dijkstra.Edge(3, 5, 5, false),
            new Dijkstra.Edge(3, 6, 40, true),
            new Dijkstra.Edge(6, 5, 40, true),
            new Dijkstra.Edge(7, 8, 10, true)
    ));

    @Test
    void prefersShorterMultiHopPathOverLongDirectEdge() {
        Dijkstra.Path path = graph.shortestPath(2, 4, false).orElseThrow();
        assertThat(path.nodeIds()).containsExactly(2L, 3L, 4L);
        assertThat(path.totalDistance()).isEqualTo(20);
        assertThat(path.edges()).hasSize(2);
    }

    @Test
    void edgesAreBidirectional() {
        Dijkstra.Path path = graph.shortestPath(4, 1, false).orElseThrow();
        assertThat(path.nodeIds()).containsExactly(4L, 3L, 2L, 1L);
        assertThat(path.totalDistance()).isEqualTo(120);
    }

    @Test
    void accessibleOnlySkipsStairsAndTakesLongerElevatorRoute() {
        Dijkstra.Path stairs = graph.shortestPath(3, 5, false).orElseThrow();
        assertThat(stairs.nodeIds()).containsExactly(3L, 5L);
        assertThat(stairs.totalDistance()).isEqualTo(5);
        assertThat(stairs.edges().get(0).accessible()).isFalse();

        Dijkstra.Path elevator = graph.shortestPath(3, 5, true).orElseThrow();
        assertThat(elevator.nodeIds()).containsExactly(3L, 6L, 5L);
        assertThat(elevator.totalDistance()).isEqualTo(80);
        assertThat(elevator.edges()).allMatch(Dijkstra.Edge::accessible);
    }

    @Test
    void unreachableNodeGivesEmpty() {
        assertThat(graph.shortestPath(1, 7, false)).isEmpty();
        assertThat(graph.shortestPath(1, 999, false)).isEmpty();
    }

    @Test
    void sameNodeGivesZeroLengthPath() {
        Optional<Dijkstra.Path> path = graph.shortestPath(3, 3, true);
        assertThat(path).isPresent();
        assertThat(path.get().nodeIds()).containsExactly(3L);
        assertThat(path.get().totalDistance()).isZero();
        assertThat(path.get().edges()).isEmpty();
    }

    @Test
    void rejectsNonPositiveDistance() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Dijkstra.Edge(1, 2, 0, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
