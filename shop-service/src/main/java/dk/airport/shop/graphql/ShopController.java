package dk.airport.shop.graphql;

import dk.airport.shop.ai.AiConciergeService;
import dk.airport.shop.ai.AiRouteAnswer;
import dk.airport.shop.domain.*;
import dk.airport.shop.graphql.input.ShopFilter;
import dk.airport.shop.graphql.input.ShopInput;
import dk.airport.shop.service.RouteService;
import dk.airport.shop.service.ShopService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Controller
@Validated
public class ShopController {

    private final ShopService shopService;
    private final RouteService routeService;
    private final AiConciergeService conciergeService;

    public ShopController(ShopService shopService, RouteService routeService, AiConciergeService conciergeService) {
        this.shopService = shopService;
        this.routeService = routeService;
        this.conciergeService = conciergeService;
    }

    // ---------------------------------------------------------- queries

    @QueryMapping
    public List<Shop> shops(@Argument ShopFilter filter) {
        return shopService.shops(filter);
    }

    @QueryMapping
    public Shop shop(@Argument Long id) {
        return shopService.shop(id).orElse(null);
    }

    @QueryMapping
    public List<Shop> searchShops(@Argument @NotBlank String text) {
        return shopService.search(text);
    }

    @QueryMapping
    public List<NavNode> navNodes(@Argument String terminal, @Argument Integer floor) {
        return shopService.navNodes(terminal, floor);
    }

    @QueryMapping
    public List<NavEdge> navEdges(@Argument String terminal) {
        return shopService.navEdges(terminal);
    }

    @QueryMapping
    public Route route(@Argument Long fromNodeId, @Argument Long toNodeId, @Argument Boolean accessibleOnly) {
        return routeService.route(fromNodeId, toNodeId, Boolean.TRUE.equals(accessibleOnly));
    }

    /** Public like the other reads: the question is free text (validated here), the answer never needs a login. */
    @QueryMapping
    public AiRouteAnswer askRoute(@Argument @NotBlank @Size(max = 500) String question, @Argument Long fromNodeId,
                                  @Argument Boolean accessibleOnly) {
        return conciergeService.askRoute(question, fromNodeId, Boolean.TRUE.equals(accessibleOnly));
    }

    // ---------------------------------------------------------- nested fields

    @SchemaMapping(typeName = "Shop")
    public boolean openNow(Shop shop) {
        return shopService.isOpenNow(shop);
    }

    @SchemaMapping(typeName = "NavNode", field = "shops")
    public List<Shop> shopsAtNode(NavNode node) {
        return shopService.shopsAtNode(node.getId());
    }

    @SchemaMapping(typeName = "NavEdge")
    public NavNode from(NavEdge edge) {
        return edge.getFromNode();
    }

    @SchemaMapping(typeName = "NavEdge")
    public NavNode to(NavEdge edge) {
        return edge.getToNode();
    }

    // ---------------------------------------------------------- mutations

    @MutationMapping
    @PreAuthorize("hasRole('OPERATIONS')")
    public Shop createShop(@Argument @Valid ShopInput input,
                           @Argument @Size(max = 64, message = "idempotencyKey must be at most 64 characters")
                           String idempotencyKey) {
        return shopService.createShop(input, idempotencyKey);
    }

    @MutationMapping
    @PreAuthorize("hasRole('OPERATIONS')")
    public Shop updateShop(@Argument Long id, @Argument @Valid ShopInput input) {
        return shopService.updateShop(id, input);
    }

    @MutationMapping
    @PreAuthorize("hasRole('OPERATIONS')")
    public boolean deleteShop(@Argument Long id) {
        return shopService.deleteShop(id);
    }
}
