package dk.airport.shop.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

/**
 * A shop in the airport. Deleting a shop leaves a tombstone ({@code deleted_at}, dev plan DP-29) instead of removing
 * the row: {@link SQLRestriction} adds {@code deleted_at IS NULL} to every query Hibernate generates for this entity
 * (findById, findAll, specifications, JPQL), so a deleted shop disappears from the whole service without any
 * repository having to remember a filter.
 */
@Entity
@Table(name = "shop")
@SQLRestriction("deleted_at IS NULL")
public class Shop {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShopCategory category;

    @Column(nullable = false, length = 5)
    private String terminal;

    @Column(nullable = false, length = 50)
    private String zone;

    @Column(nullable = false)
    private Integer floor;

    @Column(name = "opening_hours", nullable = false, length = 50)
    private String openingHours;

    @Column(columnDefinition = "text")
    private String description;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "node_id")
    private NavNode node;

    /** Null while the shop exists; the time it was deleted (tombstone) otherwise. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** The client's key for the createShop call that created this shop (DP-30); null when it sent none. */
    @Column(name = "idempotency_key", unique = true, length = 64)
    private String idempotencyKey;

    protected Shop() {}

    public Shop(String name, ShopCategory category, String terminal, String zone, Integer floor,
                String openingHours, String description, NavNode node) {
        this.name = name;
        this.category = category;
        this.terminal = terminal;
        this.zone = zone;
        this.floor = floor;
        this.openingHours = openingHours;
        this.description = description;
        this.node = node;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public ShopCategory getCategory() { return category; }
    public String getTerminal() { return terminal; }
    public String getZone() { return zone; }
    public Integer getFloor() { return floor; }
    public String getOpeningHours() { return openingHours; }
    public String getDescription() { return description; }
    public NavNode getNode() { return node; }
    public Instant getDeletedAt() { return deletedAt; }
    public String getIdempotencyKey() { return idempotencyKey; }

    public void setName(String name) { this.name = name; }
    public void setCategory(ShopCategory category) { this.category = category; }
    public void setTerminal(String terminal) { this.terminal = terminal; }
    public void setZone(String zone) { this.zone = zone; }
    public void setFloor(Integer floor) { this.floor = floor; }
    public void setOpeningHours(String openingHours) { this.openingHours = openingHours; }
    public void setDescription(String description) { this.description = description; }
    public void setNode(NavNode node) { this.node = node; }

    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    /** Marks the shop as deleted; from the next query on it is invisible to the service. */
    public void markDeleted(Instant when) { this.deletedAt = when; }
}
