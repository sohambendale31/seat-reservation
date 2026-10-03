package com.seatres.service;

import com.seatres.domain.SeatRow;
import com.seatres.domain.ShowEntity;
import com.seatres.error.ShowNotFoundException;
import com.seatres.repository.SeatJdbcRepository;
import com.seatres.repository.ShowJpaRepository;
import com.seatres.service.tx.TxExecutor;
import com.seatres.web.dto.CreateShowRequest;
import com.seatres.web.dto.RowSpec;
import com.seatres.web.dto.SeatCounts;
import com.seatres.web.dto.SeatView;
import com.seatres.web.dto.ShowDetailsResponse;
import com.seatres.web.dto.ShowResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ShowService {

    private final TxExecutor txExecutor;
    private final ShowJpaRepository shows;
    private final SeatJdbcRepository seats;

    public ShowService(TxExecutor txExecutor, ShowJpaRepository shows, SeatJdbcRepository seats) {
        this.txExecutor = txExecutor;
        this.shows = shows;
        this.seats = seats;
    }

    public ShowResponse create(CreateShowRequest request) {
        UUID showId = UUID.randomUUID();
        // Truncated to the precision PostgreSQL stores, so this response equals a later GET.
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ShowEntity show = new ShowEntity(showId, request.trimmedName(),
                request.startsAt().toInstant(), request.perUserLimitOrDefault(),
                request.totalSeats(), createdAt);

        return txExecutor.execute(context -> {
            shows.saveAndFlush(show);
            seats.insertAll(showId, layoutOf(request));
            return toResponse(show);
        });
    }

    public ShowDetailsResponse details(UUID showId) {
        ShowEntity show = shows.findById(showId).orElseThrow(() -> new ShowNotFoundException(showId));
        List<SeatRow> rows = seats.findByShow(showId);
        return new ShowDetailsResponse(show.getId(), show.getName(), show.getStartsAt(),
                show.getPerUserLimit(), show.getTotalSeats(), show.getCreatedAt(),
                countsOf(rows), viewsOf(rows));
    }

    private static List<SeatJdbcRepository.NewSeat> layoutOf(CreateShowRequest request) {
        List<SeatJdbcRepository.NewSeat> layout = new ArrayList<>(request.totalSeats());
        for (RowSpec row : request.rows()) {
            for (int number = 1; number <= row.seatCount(); number++) {
                layout.add(new SeatJdbcRepository.NewSeat(row.row() + number, row.pricePaise()));
            }
        }
        return layout;
    }

    private static SeatCounts countsOf(List<SeatRow> rows) {
        int available = 0;
        int held = 0;
        int confirmed = 0;
        for (SeatRow row : rows) {
            switch (row.status()) {
                case AVAILABLE -> available++;
                case HELD -> held++;
                case CONFIRMED -> confirmed++;
            }
        }
        return new SeatCounts(rows.size(), available, held, confirmed);
    }

    private static List<SeatView> viewsOf(List<SeatRow> rows) {
        return rows.stream()
                .map(row -> new SeatView(row.label(), row.status(), row.pricePaise()))
                .toList();
    }

    private static ShowResponse toResponse(ShowEntity show) {
        return new ShowResponse(show.getId(), show.getName(), show.getStartsAt(),
                show.getPerUserLimit(), show.getTotalSeats(), show.getCreatedAt());
    }
}
