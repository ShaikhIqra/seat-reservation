package com.iqra.seat_reservation.reservation;

import java.util.List;
import java.util.UUID;

public record CancelledRow(UUID showId, List<String> seats) {}
