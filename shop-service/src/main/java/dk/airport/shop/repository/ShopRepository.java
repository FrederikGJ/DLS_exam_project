package dk.airport.shop.repository;

import dk.airport.shop.domain.Shop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface ShopRepository extends JpaRepository<Shop, Long>, JpaSpecificationExecutor<Shop> {

    @Query("select s from Shop s where lower(s.name) like :text or lower(cast(s.category as string)) like :text " +
           "or lower(coalesce(s.description, '')) like :text or lower(s.zone) like :text order by s.name")
    List<Shop> search(String text);

    List<Shop> findByNodeIdOrderByName(Long nodeId);

    List<Shop> findByNodeIdIn(Collection<Long> nodeIds);
}
