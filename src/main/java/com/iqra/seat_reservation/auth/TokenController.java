package com.iqra.seat_reservation.auth;

import com.iqra.seat_reservation.common.ApiException;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@RestController
public class TokenController {
    private final JwtEncoder encoder;
    private final String adminKey;

    public TokenController(JwtEncoder encoder, @Value("${app.admin-key}") String adminKey) {
        this.encoder = encoder;
        this.adminKey = adminKey;
    }

    @PostMapping("/auth/token")
    public Map<String, String> token(
            @Valid @RequestBody TokenRequest req,
            @RequestHeader(value = "X-Admin-Key", required = false) String providedKey) {

        boolean admin = "admin".equals(req.role());
        if (admin && !adminKey.equals(providedKey)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "admin_key_required",
                    "A valid X-Admin-Key is required for admin tokens");
        }

        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(req.userId())
                .issuedAt(now)
                .expiresAt(now.plus(24, ChronoUnit.HOURS))
                .claim("scope", admin ? "admin" : "user")
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return Map.of("token", token);
    }
}