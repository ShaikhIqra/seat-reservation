package com.iqra.seat_reservation.reservation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ReservationMetrics {
    private static final List<String> STATUSES = List.of("available", "held", "confirmed");

    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;
    private final MultiGauge seatsGauge;

    public ReservationMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.seatsGauge = MultiGauge.builder("seats.by.status")
                .description("Seats per show by status, read from the database")
                .register(registry);
    }

    public void confirmed(UUID showId) {
        registry.counter("reservations.confirmed", "show_id", showId.toString()).increment();
    }

    public void declined(UUID showId, String reason) {
        registry.counter("reservations.declined", "show_id", showId.toString(), "reason", reason).increment();
    }

    public void cancelled(UUID showId) {
        registry.counter("reservations.cancelled", "show_id", showId.toString()).increment();
    }

    /** Re-read seat counts from the DB every 5s. Statuses with no seats are reported as 0. */
    @Scheduled(fixedRate = 5000)
    public void refreshSeatGauges() {
        Map<String, Map<String, Long>> counts = new HashMap<>();
        jdbc.query(
                "SELECT show_id::text AS show_id, status, count(*) AS n FROM seats GROUP BY show_id, status",
                (RowCallbackHandler) rs -> counts
                        .computeIfAbsent(rs.getString("show_id"), k -> new HashMap<>())
                        .put(rs.getString("status"), rs.getLong("n")));

        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        counts.forEach((showId, byStatus) -> {
            for (String status : STATUSES) {
                rows.add(MultiGauge.Row.of(
                        Tags.of("show_id", showId, "status", status),
                        byStatus.getOrDefault(status, 0L)));
            }
        });
        seatsGauge.register(rows, true);
    }
}
