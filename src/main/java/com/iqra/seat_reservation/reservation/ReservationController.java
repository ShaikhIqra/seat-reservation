package com.iqra.seat_reservation.reservation;

import com.iqra.seat_reservation.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Set;
import java.util.UUID;

@RestController
public class ReservationController {
    private static final Set<String> DECLINE_REASONS =
            Set.of("seat_taken", "per_user_limit", "idempotency_key_reused", "unknown_seat");

    private final ReservationService service;
    private final ReservationMetrics metrics;

    public ReservationController(ReservationService service, ReservationMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }
    @PostMapping("/shows/{showId}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable UUID showId,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ReserveRequest req,
            HttpServletRequest request) {

        MDC.put("user_id", jwt.getSubject());

        try {
            ReserveResult result = service.reserve(showId, jwt.getSubject(), req);
            if (result.replay()) {
                metrics.declined(showId, "idempotent_replay");
                request.setAttribute("outcome", "idempotent_replay");
                return ResponseEntity.ok(result.reservation());
            }
            metrics.confirmed(showId);
            request.setAttribute("outcome", "confirmed");
            return ResponseEntity.status(HttpStatus.CREATED).body(result.reservation());
        } catch (ApiException e) {
            if (DECLINE_REASONS.contains(e.getCode())) {
                metrics.declined(showId, e.getCode());
            }
            request.setAttribute("outcome", e.getCode());
            throw e;
        }
    }

    @PostMapping("/reservations/{id}/cancel")
    public ReservationResponse cancel(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {

        MDC.put("user_id", jwt.getSubject());

        CancelResult result = service.cancel(id, jwt.getSubject());
        if (result.changed()) {
            metrics.cancelled(result.reservation().showId());
        }
        request.setAttribute("outcome", result.changed() ? "cancelled" : "already_cancelled");
        return result.reservation();
    }
}
