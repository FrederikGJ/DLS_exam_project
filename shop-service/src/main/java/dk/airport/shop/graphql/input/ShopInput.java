package dk.airport.shop.graphql.input;

import dk.airport.shop.domain.ShopCategory;
import jakarta.validation.constraints.*;

public record ShopInput(
        @NotBlank @Size(max = 100) String name,
        @NotNull ShopCategory category,
        @NotBlank @Size(max = 5) String terminal,
        @NotBlank @Size(max = 50) String zone,
        @NotNull @Min(-2) @Max(10) Integer floor,
        @NotBlank @Pattern(regexp = "^(\\d{2}:\\d{2}-\\d{2}:\\d{2}|24/7)$",
                message = "openingHours must be HH:MM-HH:MM or 24/7") String openingHours,
        @Size(max = 2000) String description,
        Long nodeId
) {}
