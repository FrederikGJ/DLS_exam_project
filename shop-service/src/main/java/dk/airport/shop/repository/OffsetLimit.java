package dk.airport.shop.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Ticket 17.1: limit/offset pagination for list queries. Spring's PageRequest only knows page numbers, so an
 * arbitrary offset (e.g. 30 with limit 20) needs its own Pageable. Same class in each service (no shared library).
 */
public record OffsetLimit(long offset, int limit, Sort sort) implements Pageable {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    /** null means "not given": offset 0, limit {@value #DEFAULT_LIMIT}. Range checks are done in the controller. */
    public static OffsetLimit of(Integer offset, Integer limit, Sort sort) {
        return new OffsetLimit(offset == null ? 0 : offset, limit == null ? DEFAULT_LIMIT : limit, sort);
    }

    @Override
    public int getPageNumber() {
        return (int) (offset / limit);
    }

    @Override
    public int getPageSize() {
        return limit;
    }

    @Override
    public long getOffset() {
        return offset;
    }

    @Override
    public Sort getSort() {
        return sort;
    }

    @Override
    public Pageable next() {
        return new OffsetLimit(offset + limit, limit, sort);
    }

    @Override
    public Pageable previousOrFirst() {
        return hasPrevious() ? new OffsetLimit(Math.max(0, offset - limit), limit, sort) : first();
    }

    @Override
    public Pageable first() {
        return new OffsetLimit(0, limit, sort);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new OffsetLimit((long) pageNumber * limit, limit, sort);
    }

    @Override
    public boolean hasPrevious() {
        return offset > 0;
    }
}
