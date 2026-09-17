package dk.airport.booking.query;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One bag of a booking as the read model knows it, built from baggage.registered and baggage.status.changed
 * (baggage-service owns the bag). Stored in the JSON column {@code booking_overview.baggage}.
 *
 * <p>Status and location belong together and come from the newest event ({@code updatedAt} = its
 * {@code occurredAt}); type and weight are only in baggage.registered and are kept from whichever event had them.
 */
public record OverviewBaggage(String tagNumber, String type, BigDecimal weightKg, String status, String lastLocation,
                              OffsetDateTime registeredAt, OffsetDateTime updatedAt) {

    /** Combines two observations of the same bag, independent of arrival order - see {@link OverviewPayment#merge}. */
    public OverviewBaggage merge(OverviewBaggage other) {
        OverviewBaggage newer = Lines.newer(this, other, OverviewBaggage::updatedAt,
                b -> b.status + '@' + b.lastLocation);
        OverviewBaggage older = newer == this ? other : this;
        return new OverviewBaggage(tagNumber,
                Lines.firstNonNull(newer.type, older.type),
                Lines.firstNonNull(newer.weightKg, older.weightKg),
                newer.status,
                newer.lastLocation,
                Lines.earliest(registeredAt, other.registeredAt),
                newer.updatedAt);
    }
}
