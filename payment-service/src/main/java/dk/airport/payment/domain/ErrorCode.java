package dk.airport.payment.domain;

/** Error codes exposed to GraphQL clients in errors[].extensions.code */
public enum ErrorCode {
    NOT_FOUND,
    VALIDATION_ERROR,
    INVALID_STATE,
    ALREADY_PAID,
    PAYMENT_FAILED,
    CONFLICT,
    INTERNAL_ERROR
}
