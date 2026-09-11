package dk.airport.shop.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "nav_node")
public class NavNode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 5)
    private String terminal;

    @Column(nullable = false)
    private Integer floor;

    @Column(nullable = false)
    private Integer x;

    @Column(nullable = false)
    private Integer y;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NodeType type;

    protected NavNode() {}

    public NavNode(String name, String terminal, Integer floor, Integer x, Integer y, NodeType type) {
        this.name = name;
        this.terminal = terminal;
        this.floor = floor;
        this.x = x;
        this.y = y;
        this.type = type;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getTerminal() { return terminal; }
    public Integer getFloor() { return floor; }
    public Integer getX() { return x; }
    public Integer getY() { return y; }
    public NodeType getType() { return type; }
}
