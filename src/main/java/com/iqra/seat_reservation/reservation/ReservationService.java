package com.iqra.seat_reservation.reservation;

import com.iqra.seat_reservation.common.ApiException;
import com.iqra.seat_reservation.show.ShowRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReservationService {
    private final ReservationRepository repo;
    private final ShowRepository showRepo;

    public ReservationService(ReservationRepository repo, ShowRepository showRepo) {
        this.repo = repo;
        this.showRepo = showRepo;
    }

    @Transactional
    public ReserveResult reserve(UUID showId, String userId, ReserveRequest req) {
        // 1. Sort (topic 3: same lock order for everyone) and validate
        List<String> seats = req.seats().stream().sorted().toList();
        if (new HashSet<>(seats).size() != seats.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "duplicate_seats", "Seat labels must be unique");
        }

        // 2. Show must exist
        var show = showRepo.findShow(showId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "show_not_found", "Show not found"));

        // 3. Idempotency (topic 4)
        String fingerprint = showId + ":" + String.join(",", seats);
        boolean firstTime = repo.insertIdempotencyKey(userId, req.idempotencyKey(), fingerprint);
        if (!firstTime) {
            IdempotencyRecord existing = repo.findIdempotencyKey(userId, req.idempotencyKey()).orElseThrow();
            if (!existing.fingerprint().equals(fingerprint)) {
                throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_reused",
                        "This idempotency key was already used for a different request");
            }
            ReservationResponse original = repo.findReservation(existing.reservationId()).orElseThrow();
            return new ReserveResult(original, true);
        }

        // 4. Claim each seat atomically (topics 1–3)
        UUID reservationId = UUID.randomUUID();
        for (String seat : seats) {
            int rows = repo.claimSeat(showId, seat, userId, reservationId);
            if (rows == 0) {
                if (!repo.seatExists(showId, seat)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "unknown_seat", "Seat " + seat + " does not exist");
                }
                throw new ApiException(HttpStatus.CONFLICT, "seat_taken", "Seat " + seat + " is already taken");
            }
        }

        // 5–6. Record the reservation and link it to the key
        long amount = show.pricePaise() * seats.size();
        repo.insertReservation(reservationId, showId, userId, seats, amount);
        repo.setIdempotencyReservation(userId, req.idempotencyKey(), reservationId);

        if (seats.size() > show.perUserLimit()) {
            throw new ApiException(HttpStatus.CONFLICT, "per_user_limit",
                    "Request exceeds the per-user limit of " + show.perUserLimit());
        }
        repo.ensureHoldRow(showId, userId);
        if (repo.reserveQuota(showId, userId, seats.size(), show.perUserLimit()) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "per_user_limit",
                    "Per-user limit of " + show.perUserLimit() + " seats reached");
        }

        return new ReserveResult(
                new ReservationResponse(reservationId, showId, userId, seats, amount, "confirmed"), false);
    }

    @Transactional
    public CancelResult cancel(UUID reservationId, String userId) {
        Optional<CancelledRow> cancelled = repo.markCancelled(reservationId, userId);

        if (cancelled.isEmpty()) {
            ReservationResponse existing = repo.findReservation(reservationId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "reservation_not_found", "Reservation not found"));
            if (!existing.userId().equals(userId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "not_owner",
                        "You can only cancel your own reservations");
            }
            return new CancelResult(existing, false);   // already cancelled: nothing changed
        }

        CancelledRow row = cancelled.get();
        repo.releaseQuota(row.showId(), userId, row.seats().size());    // counter first (same order as reserve)
        int released = repo.releaseSeats(row.showId(), reservationId);  // then seats

        if (released != row.seats().size()) {
            // Should be impossible; throwing rolls everything back
            throw new IllegalStateException("Released " + released + " seats, expected " + row.seats().size());
        }
        return new CancelResult(repo.findReservation(reservationId).orElseThrow(), true);
    }

}
