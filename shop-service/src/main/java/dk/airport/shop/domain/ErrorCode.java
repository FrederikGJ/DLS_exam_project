package dk.airport.shop.domain;

/** Error codes exposed to GraphQL clients in errors[].extensions.code */
public enum ErrorCode {
    NOT_FOUND,
    VALIDATION_ERROR,
    ROUTE_NOT_FOUND,
    CONFLICT,
    UNAUTHORIZED,       // no (valid) token, but the operation requires a login
    FORBIDDEN,          // logged in, but the role does not allow the operation
    INTERNAL_ERROR
}
