package dk.airport.baggage.service;

import java.security.SecureRandom;

/** Generates baggage tags of the form BAG-XXXXXXXX (8 chars from A-Z0-9). */
public final class TagGenerator {

    public static final String PREFIX = "BAG-";
    public static final int LENGTH = 8;
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private TagGenerator() {}

    public static String generate() {
        StringBuilder sb = new StringBuilder(PREFIX);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
