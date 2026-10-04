package com.iqra.seat_reservation.reservation;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.UUID;

@RestController
public class ReservationController {
    private final ReservationService service;

    public ReservationController(ReservationService service) { this.service = service; }

    @PostMapping("/shows/{showId}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable UUID showId,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ReserveRequest req) {
        String userId = jwt.getSubject();
        ReserveResult result = service.reserve(showId, userId, req);
        HttpStatus status = result.replay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result.reservation());
    }
}
