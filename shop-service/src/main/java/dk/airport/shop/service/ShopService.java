package dk.airport.shop.service;

import dk.airport.shop.domain.*;
import dk.airport.shop.graphql.input.ShopFilter;
import dk.airport.shop.graphql.input.ShopInput;
import dk.airport.shop.repository.NavEdgeRepository;
import dk.airport.shop.repository.NavNodeRepository;
import dk.airport.shop.repository.ShopRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class ShopService {

    private static final Logger log = LoggerFactory.getLogger(ShopService.class);

    private final ShopRepository shops;
    private final NavNodeRepository nodes;
    private final NavEdgeRepository edges;
    private final ZoneId zone;

    public ShopService(ShopRepository shops, NavNodeRepository nodes, NavEdgeRepository edges,
                       @Value("${app.timezone:Europe/Copenhagen}") String timezone) {
        this.shops = shops;
        this.nodes = nodes;
        this.edges = edges;
        this.zone = ZoneId.of(timezone);
    }

    // ------------------------------------------------------------ shops

    public List<Shop> shops(ShopFilter filter) {
        Specification<Shop> spec = Specification.where(null);
        if (filter != null) {
            if (filter.terminal() != null && !filter.terminal().isBlank()) {
                String terminal = filter.terminal().trim().toUpperCase();
                spec = spec.and((root, q, cb) -> cb.equal(cb.upper(root.get("terminal")), terminal));
            }
            if (filter.category() != null) {
                spec = spec.and((root, q, cb) -> cb.equal(root.get("category"), filter.category()));
            }
        }
        List<Shop> result = shops.findAll(spec, Sort.by("terminal", "name"));
        if (filter != null && filter.openNow() != null) {
            boolean wantOpen = filter.openNow();
            LocalTime now = LocalTime.now(Clock.system(zone));
            result = result.stream().filter(s -> OpeningHours.isOpen(s.getOpeningHours(), now) == wantOpen).toList();
        }
        return result;
    }

    public Optional<Shop> shop(Long id) {
        return shops.findById(id);
    }

    public List<Shop> search(String text) {
        String needle = "%" + text.trim().toLowerCase() + "%";
        return shops.search(needle);
    }

    public List<Shop> shopsAtNode(Long nodeId) {
        return shops.findByNodeIdOrderByName(nodeId);
    }

    public boolean isOpenNow(Shop shop) {
        return OpeningHours.isOpen(shop.getOpeningHours(), LocalTime.now(Clock.system(zone)));
    }

    @Transactional
    public Shop createShop(ShopInput in) {
        return createShop(in, null);
    }

    /**
     * Creates a shop, idempotently when the client sends a key (dev plan DP-30): a repeated call with the same key
     * returns the shop the first call created instead of a duplicate. A key whose shop has since been deleted is
     * CONFLICT (the tombstone keeps the key taken). Two calls racing with the same key are settled by the unique
     * constraint: the loser gets CONFLICT and a retry of it returns the winner's shop.
     */
    @Transactional
    public Shop createShop(ShopInput in, String idempotencyKey) {
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (key != null) {
            Optional<Shop> earlier = shops.findByIdempotencyKey(key);
            if (earlier.isPresent()) {
                log.info("Idempotent replay: shop {} ({}) was already created with this key",
                        earlier.get().getName(), earlier.get().getId());
                return earlier.get();
            }
            if (shops.idempotencyKeyUsed(key)) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "The idempotency key was used for a shop that has since been deleted");
            }
        }
        NavNode node = resolveNode(in.nodeId());
        Shop shop = new Shop(in.name().trim(), in.category(), in.terminal().trim().toUpperCase(),
                in.zone().trim(), in.floor(), in.openingHours().trim(), trimToNull(in.description()), node);
        shop.setIdempotencyKey(key);
        shop = shops.saveAndFlush(shop);
        log.info("Created shop {} ({})", shop.getName(), shop.getId());
        return shop;
    }

    @Transactional
    public Shop updateShop(Long id, ShopInput in) {
        Shop shop = shops.findById(id).orElseThrow(() -> ApiException.notFound("Shop", id));
        shop.setName(in.name().trim());
        shop.setCategory(in.category());
        shop.setTerminal(in.terminal().trim().toUpperCase());
        shop.setZone(in.zone().trim());
        shop.setFloor(in.floor());
        shop.setOpeningHours(in.openingHours().trim());
        shop.setDescription(trimToNull(in.description()));
        shop.setNode(resolveNode(in.nodeId()));
        log.info("Updated shop {} ({})", shop.getName(), shop.getId());
        return shop;
    }

    /**
     * Deletes a shop by leaving a tombstone (dev plan DP-29): the row gets {@code deleted_at} and is filtered out of
     * every later query, so {@code shop(id)} returns null and {@code updateShop}/{@code deleteShop} give NOT_FOUND.
     * Nothing is physically deleted.
     */
    @Transactional
    public boolean deleteShop(Long id) {
        Shop shop = shops.findById(id).orElseThrow(() -> ApiException.notFound("Shop", id));
        shop.markDeleted(Instant.now());
        log.info("Deleted shop {} ({}) - tombstone kept", shop.getName(), id);
        return true;
    }

    private NavNode resolveNode(Long nodeId) {
        if (nodeId == null) {
            return null;
        }
        return nodes.findById(nodeId).orElseThrow(() -> ApiException.notFound("NavNode", nodeId));
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ------------------------------------------------------------ navigation graph

    public List<NavNode> navNodes(String terminal, Integer floor) {
        Specification<NavNode> spec = Specification.where(null);
        if (terminal != null && !terminal.isBlank()) {
            String t = terminal.trim().toUpperCase();
            spec = spec.and((root, q, cb) -> cb.equal(cb.upper(root.get("terminal")), t));
        }
        if (floor != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("floor"), floor));
        }
        return nodes.findAll(spec, Sort.by("terminal", "floor", "name"));
    }

    public Optional<NavNode> navNode(Long id) {
        return nodes.findById(id);
    }

    public List<NavEdge> navEdges(String terminal) {
        return terminal == null || terminal.isBlank()
                ? edges.findAllWithNodes()
                : edges.findByTerminalWithNodes(terminal.trim().toUpperCase());
    }
}
