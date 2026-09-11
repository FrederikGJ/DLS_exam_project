package dk.airport.shop.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "shop")
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

    public void setName(String name) { this.name = name; }
    public void setCategory(ShopCategory category) { this.category = category; }
    public void setTerminal(String terminal) { this.terminal = terminal; }
    public void setZone(String zone) { this.zone = zone; }
    public void setFloor(Integer floor) { this.floor = floor; }
    public void setOpeningHours(String openingHours) { this.openingHours = openingHours; }
    public void setDescription(String description) { this.description = description; }
    public void setNode(NavNode node) { this.node = node; }
}
