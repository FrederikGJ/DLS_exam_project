package dk.airport.shop.repository;

import dk.airport.shop.domain.Shop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ShopRepository extends JpaRepository<Shop, Long>, JpaSpecificationExecutor<Shop> {

    @Query("select s from Shop s where lower(s.name) like :text or lower(cast(s.category as string)) like :text " +
           "or lower(coalesce(s.description, '')) like :text or lower(s.zone) like :text order by s.name")
    List<Shop> search(String text);

    List<Shop> findByNodeIdOrderByName(Long nodeId);

    List<Shop> findByNodeIdIn(Collection<Long> nodeIds);

    /** Active shops only (the entity's tombstone restriction applies). */
    Optional<Shop> findByIdempotencyKey(String idempotencyKey);

    /** Native SQL on purpose: also sees deleted shops, which the entity's @SQLRestriction hides. */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM shop WHERE idempotency_key = :key)", nativeQuery = true)
    boolean idempotencyKeyUsed(@Param("key") String key);
}
