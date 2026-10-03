package com.iqra.seat_reservation.show;

import com.iqra.seat_reservation.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
public class ShowService {
    private final ShowRepository repo;

    public ShowService(ShowRepository repo) { this.repo = repo; }

    @Transactional
    public ShowResponse create(CreateShowRequest req) {
        if (new HashSet<>(req.seats()).size() != req.seats().size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "duplicate_seats",
                    "Seat labels must be unique");
        }
        int limit = (req.perUserLimit() == null) ? 4 : req.perUserLimit();

        UUID id = repo.insertShow(req.name(), req.pricePaise(), limit, req.seats().size());
        repo.insertSeats(id, req.seats());
        return get(id);
    }

    public ShowResponse get(UUID id) {
        ShowResponse show = repo.findShow(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "show_not_found",
                        "Show not found"));

        List<SeatView> seats = repo.findSeats(id);
        int available = 0, held = 0, confirmed = 0;
        for (SeatView s : seats) {
            switch (s.status()) {
                case "available" -> available++;
                case "held"      -> held++;
                case "confirmed" -> confirmed++;
            }
        }
        SeatCounts counts = new SeatCounts(available, held, confirmed, seats.size());

        return new ShowResponse(show.id(), show.name(), show.pricePaise(),
                show.perUserLimit(), show.totalSeats(), counts, seats);
    }
}
