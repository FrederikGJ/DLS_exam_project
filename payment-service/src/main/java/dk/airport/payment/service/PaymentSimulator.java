package dk.airport.payment.service;

import dk.airport.payment.domain.ApiException;
import dk.airport.payment.domain.ErrorCode;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simulated payment gateway. Pure domain logic, no I/O.
 * <ul>
 *   <li>card number ending in "0000"  -> declined, "Insufficient funds"</li>
 *   <li>expiry (MM/YY or MM/YYYY) in the past -> declined, "Card expired"</li>
 *   <li>anything else -> approved</li>
 * </ul>
 * The full card number is never stored or logged; use {@link #cardLast4(String)}.
 */
public class PaymentSimulator {

    public static final String INSUFFICIENT_FUNDS = "Insufficient funds";
    public static final String CARD_EXPIRED = "Card expired";

    private static final Pattern EXPIRY = Pattern.compile("^(0[1-9]|1[0-2])/(\\d{2}(?:\\d{2})?)$");

    private final Clock clock;

    public PaymentSimulator(Clock clock) {
        this.clock = clock;
    }

    public record Result(boolean approved, String failureReason) {
        static Result ok() { return new Result(true, null); }
        static Result declined(String reason) { return new Result(false, reason); }
    }

    public Result evaluate(String cardNumber, String expiry) {
        String digits = normalize(cardNumber);
        YearMonth expiryMonth = parseExpiry(expiry);
        LocalDate lastValidDay = expiryMonth.atEndOfMonth();
        LocalDate today = LocalDate.now(clock);
        if (lastValidDay.isBefore(today)) {
            return Result.declined(CARD_EXPIRED);
        }
        if (digits.endsWith("0000")) {
            return Result.declined(INSUFFICIENT_FUNDS);
        }
        return Result.ok();
    }

    public static String cardLast4(String cardNumber) {
        String digits = normalize(cardNumber);
        if (digits.length() < 4) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "cardNumber must contain at least 4 digits");
        }
        return digits.substring(digits.length() - 4);
    }

    public static YearMonth parseExpiry(String expiry) {
        if (expiry == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "expiry is required (MM/YY)");
        }
        Matcher m = EXPIRY.matcher(expiry.trim());
        if (!m.matches()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "expiry must be in format MM/YY");
        }
        int month = Integer.parseInt(m.group(1));
        String yearPart = m.group(2);
        int year = yearPart.length() == 2 ? 2000 + Integer.parseInt(yearPart) : Integer.parseInt(yearPart);
        return YearMonth.of(year, month);
    }

    private static String normalize(String cardNumber) {
        return cardNumber == null ? "" : cardNumber.replaceAll("\\s+", "");
    }
}
