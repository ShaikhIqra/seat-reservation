package com.iqra.seat_reservation.reservation;

import java.util.UUID;

public record IdempotencyRecord(String fingerprint, UUID reservationId) {}
