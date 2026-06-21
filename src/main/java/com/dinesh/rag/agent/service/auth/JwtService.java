package com.dinesh.rag.agent.service.auth;

import com.dinesh.rag.agent.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Signs and verifies HS256 JWTs. The signing key is derived from the
 * configured {@code app.jwt.secret} (must be ≥ 32 bytes for HS256).
 */
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final Duration expiration;
    private final String issuer;

    public JwtService(AppProperties props) {
        byte[] secretBytes = props.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret must be at least 32 bytes for HS256; got "
                            + secretBytes.length + " bytes.");
        }
        this.signingKey = Keys.hmacShaKeyFor(secretBytes);
        this.expiration = Duration.ofMinutes(props.jwt().expirationMinutes());
        this.issuer = props.jwt().issuer();
    }

    /**
     * Sign a token whose {@code sub} is the user's UUID.
     *
     * @return the issued token plus the absolute expiration instant
     */
    public IssuedToken issue(UUID userId, String email) {
        Instant now = Instant.now();
        Instant exp = now.plus(expiration);

        String token = Jwts.builder()
                .issuer(issuer)
                .subject(userId.toString())
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();

        return new IssuedToken(token, exp);
    }

    /**
     * Parse + verify a token. Throws {@link io.jsonwebtoken.JwtException}
     * (including expired/malformed/bad-signature) if the token is invalid.
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public record IssuedToken(String token, Instant expiresAt) {}
}
