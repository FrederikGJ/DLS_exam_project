package dk.airport.payment.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A payment attempt for a booking. Only the last 4 digits of the card are ever stored. */
@Entity
@Table(name = "payment")
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_reference", nullable = false, length = 6)
    private String bookingReference;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency = "DKK";

    @Column(name = "card_last4", nullable = false, length = 4)
    private String cardLast4;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "failure_reason", length = 200)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Payment() {}

    public Payment(String bookingReference, BigDecimal amount, String cardLast4, PaymentStatus status, String failureReason) {
        this.bookingReference = bookingReference;
        this.amount = amount;
        this.cardLast4 = cardLast4;
        this.status = status;
        this.failureReason = failureReason;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() { return id; }
    public String getBookingReference() { return bookingReference; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getCardLast4() { return cardLast4; }
    public PaymentStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setStatus(PaymentStatus status) {
        this.status = status;
        this.updatedAt = OffsetDateTime.now();
    }
}
