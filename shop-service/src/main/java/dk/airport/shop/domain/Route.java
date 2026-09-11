package dk.airport.shop.domain;

import java.util.List;

/** Result of a route calculation (GraphQL type Route). */
public record Route(List<RouteStep> steps, int totalDistanceM, int estimatedMinutes, List<Shop> shopsAlongRoute) {}
