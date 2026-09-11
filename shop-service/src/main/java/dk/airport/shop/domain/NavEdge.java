package dk.airport.shop.domain;

import jakarta.persistence.*;

/** One physical connection between two nodes. Stored once; routing treats it as bidirectional. */
@Entity
@Table(name = "nav_edge")
public class NavEdge {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "from_node_id")
    private NavNode fromNode;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "to_node_id")
    private NavNode toNode;

    @Column(name = "distance_m", nullable = false)
    private Integer distanceM;

    @Column(nullable = false)
    private boolean accessible = true;

    protected NavEdge() {}

    public NavEdge(NavNode fromNode, NavNode toNode, Integer distanceM, boolean accessible) {
        this.fromNode = fromNode;
        this.toNode = toNode;
        this.distanceM = distanceM;
        this.accessible = accessible;
    }

    public Long getId() { return id; }
    public NavNode getFromNode() { return fromNode; }
    public NavNode getToNode() { return toNode; }
    public Integer getDistanceM() { return distanceM; }
    public boolean isAccessible() { return accessible; }
}
