package dk.airport.shop.repository;

import dk.airport.shop.domain.NavEdge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface NavEdgeRepository extends JpaRepository<NavEdge, Long> {

    @Query("select e from NavEdge e join fetch e.fromNode join fetch e.toNode order by e.id")
    List<NavEdge> findAllWithNodes();

    @Query("select e from NavEdge e join fetch e.fromNode f join fetch e.toNode t " +
           "where f.terminal = :terminal or t.terminal = :terminal order by e.id")
    List<NavEdge> findByTerminalWithNodes(String terminal);
}
