package dk.airport.payment.graphql;

import dk.airport.payment.domain.Payment;
import dk.airport.payment.graphql.input.PayInput;
import dk.airport.payment.service.PaymentService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

@Controller
@Validated
public class PaymentController {

    private final PaymentService paymentService;
    private final Validator validator;

    public PaymentController(PaymentService paymentService, Validator validator) {
        this.paymentService = paymentService;
        this.validator = validator;
    }

    @QueryMapping
    public Payment payment(@Argument Long id) {
        return paymentService.payment(id).orElse(null);
    }

    @QueryMapping
    public List<Payment> paymentsByBooking(@Argument @NotBlank String reference) {
        return paymentService.paymentsByBooking(reference);
    }

    /**
     * The mutation takes scalar arguments (per the API spec); they are grouped into a {@link PayInput}
     * record that carries the Bean Validation constraints and validated explicitly.
     */
    @MutationMapping
    public Payment pay(@Argument String bookingReference, @Argument BigDecimal amount,
                       @Argument String cardNumber, @Argument String expiry, @Argument String cvv) {
        PayInput input = new PayInput(bookingReference, amount, cardNumber, expiry, cvv);
        Set<ConstraintViolation<PayInput>> violations = validator.validate(input);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        return paymentService.pay(input);
    }

    @MutationMapping
    public Payment refund(@Argument Long paymentId) {
        return paymentService.refund(paymentId);
    }
}
