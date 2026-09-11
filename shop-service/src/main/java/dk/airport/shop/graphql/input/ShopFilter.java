package dk.airport.shop.graphql.input;

import dk.airport.shop.domain.ShopCategory;

public record ShopFilter(String terminal, ShopCategory category, Boolean openNow) {}
