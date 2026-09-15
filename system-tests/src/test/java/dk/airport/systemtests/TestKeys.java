package dk.airport.systemtests;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stand-in for Keycloak's signing key (dev plan DP-27). One RSA key pair is generated per test JVM: the public half
 * is published as a JWKS document by WireMock (see {@link Stack}), the private half signs the tokens the tests send.
 * The claims mirror what Keycloak puts in a token for the {@code airport} realm, so the services validate signature,
 * issuer and roles with exactly the production code path ({@code JWK_SET_URI}, {@code OIDC_ISSUER_URI},
 * {@code KeycloakRoleConverter}) - only the key and the issuer string are ours.
 */
final class TestKeys {

    /** Sent to both services as OIDC_ISSUER_URI and used as the {@code iss} claim of every minted token. */
    static final String ISSUER = "http://keycloak.test/realms/airport";

    private static final long LIFETIME_SECONDS = 3600;
    private static final RSAKey KEY = generateKey();

    private TestKeys() {}

    /** {@code Authorization} header value for the test user anna (role PASSENGER). */
    static String passenger() {
        return bearer("anna", "anna@example.com", "PASSENGER");
    }

    /** {@code Authorization} header value for the test user ops (role OPERATIONS). */
    static String operations() {
        return bearer("ops", "ops@example.com", "OPERATIONS");
    }

    /** {@code Authorization} header value for any user/roles. */
    static String bearer(String username, String email, String... roles) {
        return "Bearer " + mintToken(username, email, roles);
    }

    /** Raw signed RS256 token with the same claims Keycloak issues for the airport realm. */
    static String mintToken(String username, String email, String... roles) {
        long now = System.currentTimeMillis();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(UUID.nameUUIDFromBytes(username.getBytes(StandardCharsets.UTF_8)).toString())
                .issueTime(new Date(now))
                .expirationTime(new Date(now + LIFETIME_SECONDS * 1000))
                .claim("typ", "Bearer")
                .claim("azp", "airport-frontend")
                .claim("preferred_username", username)
                .claim("email", email)
                .claim("email_verified", true)
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .build();
        try {
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .type(JOSEObjectType.JWT)
                    .keyID(KEY.getKeyID())
                    .build();
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The JWKS document (public key only) that WireMock serves at the services' JWK_SET_URI. */
    static String jwks() {
        return new JWKSet(KEY.toPublicJWK()).toString();
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048)
                    .keyID("system-tests-key")
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
