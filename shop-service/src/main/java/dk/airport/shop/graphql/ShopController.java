package dk.airport.shop.graphql;

import dk.airport.shop.domain.*;
import dk.airport.shop.graphql.input.ShopFilter;
import dk.airport.shop.graphql.input.ShopInput;
import dk.airport.shop.service.RouteService;
import dk.airport.shop.service.ShopService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Controller
@Validated
public class ShopController {

    private final ShopService shopService;
    private final RouteService routeService;

    public ShopController(ShopService shopService, RouteService routeService) {
        this.shopService = shopService;
        this.routeService = routeService;
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

    // ---------------------------------------------------------- nested fields

    @SchemaMapping(typeName = "Shop")
    public boolean openNow(Shop shop) {
        return shopService.isOpenNow(shop);
    }

    @SchemaMapping(typeName = "NavNode")
    public List<Shop> shops(NavNode node) {
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
    public Shop createShop(@Argument @Valid ShopInput input) {
        return shopService.createShop(input);
    }

    @MutationMapping
    public Shop updateShop(@Argument Long id, @Argument @Valid ShopInput input) {
        return shopService.updateShop(id, input);
    }

    @MutationMapping
    public boolean deleteShop(@Argument Long id) {
        return shopService.deleteShop(id);
    }
}
