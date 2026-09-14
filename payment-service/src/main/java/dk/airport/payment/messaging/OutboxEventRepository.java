package dk.airport.payment.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Oldest unpublished rows first. {@code FOR UPDATE} is belt and braces on top of the advisory lock in
     * {@link OutboxRelay}: no two transactions can ever hand the same row to the broker at the same time.
     */
    @Query(value = "SELECT * FROM outbox_event WHERE published_at IS NULL ORDER BY id LIMIT :limit FOR UPDATE",
           nativeQuery = true)
    List<OutboxEvent> findPendingBatch(@Param("limit") int limit);

    /** Backlog size - exposed as the {@code outbox.pending} gauge. */
    long countByPublishedAtIsNull();

    Optional<OutboxEvent> findByEventId(String eventId);

    @Modifying
    @Query("DELETE FROM OutboxEvent o WHERE o.publishedAt IS NOT NULL AND o.publishedAt < :before")
    int deletePublishedBefore(@Param("before") OffsetDateTime before);
}
