package dk.airport.shop.repository;

import dk.airport.shop.domain.NavNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface NavNodeRepository extends JpaRepository<NavNode, Long>, JpaSpecificationExecutor<NavNode> {
    Optional<NavNode> findFirstByNameIgnoreCase(String name);
}
