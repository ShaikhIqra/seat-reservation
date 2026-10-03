package com.iqra.seat_reservation.reservation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReservationRepository {
    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** true = first time this key is used; false = key already exists */
    public boolean insertIdempotencyKey(String userId, String key, String fingerprint) {
        int rows = jdbc.update(
                "INSERT INTO idempotency_keys (user_id, key, request_hash) VALUES (?, ?, ?) " +
                        "ON CONFLICT (user_id, key) DO NOTHING",
                userId, key, fingerprint);
        return rows == 1;
    }

    public Optional<IdempotencyRecord> findIdempotencyKey(String userId, String key) {
        return jdbc.query(
                "SELECT request_hash, reservation_id FROM idempotency_keys WHERE user_id = ? AND key = ?",
                (rs, i) -> new IdempotencyRecord(
                        rs.getString("request_hash"), rs.getObject("reservation_id", UUID.class)),
                userId, key).stream().findFirst();
    }

    public void setIdempotencyReservation(String userId, String key, UUID reservationId) {
        jdbc.update(
                "UPDATE idempotency_keys SET reservation_id = ? WHERE user_id = ? AND key = ?",
                reservationId, userId, key);
    }

    /** Topic 1: the atomic decision. 1 = won, 0 = taken or doesn't exist */
    public int claimSeat(UUID showId, String seat, String userId, UUID reservationId) {
        return jdbc.update(
                "UPDATE seats SET status = 'confirmed', user_id = ?, reservation_id = ? " +
                        "WHERE show_id = ? AND seat_label = ? AND status = 'available'",
                userId, reservationId, showId, seat);
    }

    public boolean seatExists(UUID showId, String seat) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM seats WHERE show_id = ? AND seat_label = ?",
                Integer.class, showId, seat);
        return n != null && n > 0;
    }

    public void insertReservation(UUID id, UUID showId, String userId,
                                  List<String> seats, long amountPaise) {
        jdbc.update(
                "INSERT INTO reservations (id, show_id, user_id, seats, amount_paise, status) " +
                        "VALUES (?, ?, ?, ?, ?, 'confirmed')",
                id, showId, userId, seats.toArray(new String[0]), amountPaise);
    }

    public Optional<ReservationResponse> findReservation(UUID id) {
        return jdbc.query(
                "SELECT id, show_id, user_id, seats, amount_paise, status FROM reservations WHERE id = ?",
                (rs, i) -> new ReservationResponse(
                        rs.getObject("id", UUID.class),
                        rs.getObject("show_id", UUID.class),
                        rs.getString("user_id"),
                        List.of((String[]) rs.getArray("seats").getArray()),
                        rs.getLong("amount_paise"),
                        rs.getString("status")),
                id).stream().findFirst();
    }
}
