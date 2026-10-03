package com.iqra.seat_reservation.show;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ShowRepository {
    private final JdbcTemplate jdbc;

    public ShowRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public UUID insertShow(String name, long pricePaise, int perUserLimit, int totalSeats) {
        return jdbc.queryForObject(
                "INSERT INTO shows (name, price_paise, per_user_limit, total_seats) " +
                        "VALUES (?, ?, ?, ?) RETURNING id",
                UUID.class, name, pricePaise, perUserLimit, totalSeats);
    }

    public void insertSeats(UUID showId, List<String> labels) {
        List<Object[]> rows = labels.stream()
                .map(label -> new Object[]{ showId, label })
                .toList();

        jdbc.batchUpdate("INSERT INTO seats (show_id, seat_label) VALUES (?, ?)", rows);
    }

    public Optional<ShowResponse> findShow(UUID id) {
        List<ShowResponse> rows = jdbc.query(
                "SELECT id, name, price_paise, per_user_limit, total_seats FROM shows WHERE id = ?",
                (rs, i) -> new ShowResponse(
                        rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getLong("price_paise"), rs.getInt("per_user_limit"),
                        rs.getInt("total_seats"), null, null),
                id);
        return rows.stream().findFirst();
    }

    public List<SeatView> findSeats(UUID showId) {
        return jdbc.query(
                "SELECT seat_label, status FROM seats WHERE show_id = ? ORDER BY seat_label",
                (rs, i) -> new SeatView(rs.getString("seat_label"), rs.getString("status")),
                showId);
    }
}
