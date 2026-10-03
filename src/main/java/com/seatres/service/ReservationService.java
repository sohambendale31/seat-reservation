package com.seatres.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatres.domain.ReservationStatus;
import com.seatres.domain.ReserveCommand;
import com.seatres.domain.ReserveOutcome;
import com.seatres.domain.SeatRow;
import com.seatres.domain.SeatStatus;
import com.seatres.domain.ShowEntity;
import com.seatres.error.ErrorCode;
import com.seatres.error.IdempotencyKeyReusedException;
import com.seatres.error.Problems;
import com.seatres.repository.IdempotencyJdbcRepository.StoredOutcome;
import com.seatres.repository.QuotaJdbcRepository;
import com.seatres.repository.ReservationJdbcRepository;
import com.seatres.repository.SeatJdbcRepository;
import com.seatres.repository.SeatJdbcRepository.SeatRef;
import com.seatres.repository.ShowJpaRepository;
import com.seatres.service.tx.TxExecutor;
import com.seatres.web.dto.ReservationResponse;
import com.seatres.web.dto.ReservedSeat;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The reserve transaction. Locks are always taken in the order idempotency key, quota row, then
 * seat rows by ascending id, which is what makes these transactions deadlock-free.
 *
 * <p>Response bytes are serialized before the outcome is stored, so a replay returns exactly what
 * the original request returned.
 */
@Service
public class ReservationService {

    private final TxExecutor tx;
    private final IdempotencyService idempotency;
    private final ShowJpaRepository shows;
    private final SeatJdbcRepository seats;
    private final QuotaJdbcRepository quotas;
    private final ReservationJdbcRepository reservations;
    private final ObjectMapper objectMapper;

    public ReservationService(TxExecutor tx, IdempotencyService idempotency,
            ShowJpaRepository shows, SeatJdbcRepository seats, QuotaJdbcRepository quotas,
            ReservationJdbcRepository reservations, ObjectMapper objectMapper) {
        this.tx = tx;
        this.idempotency = idempotency;
        this.shows = shows;
        this.seats = seats;
        this.quotas = quotas;
        this.reservations = reservations;
        this.objectMapper = objectMapper;
    }

    public ReserveOutcome reserve(ReserveCommand command) {
        return tx.execute(context -> {
            UUID recordId = UUID.randomUUID();
            Optional<StoredOutcome> stored = idempotency.claimOrLoad(recordId, command);
            if (stored.isPresent()) {
                if (!stored.get().fingerprint().equals(command.fingerprint())) {
                    throw new IdempotencyKeyReusedException();
                }
                context.rollbackOnly();
                return new ReserveOutcome.Replayed(stored.get().status(), stored.get().body());
            }
            return allocate(command, recordId);
        });
    }

    private ReserveOutcome allocate(ReserveCommand command, UUID recordId) {
        Optional<ShowEntity> show = shows.findById(command.showId());
        if (show.isEmpty()) {
            return decline(command, recordId, ErrorCode.SHOW_NOT_FOUND,
                    Map.of("showId", command.showId().toString()));
        }

        List<SeatRef> resolved = seats.resolveLabels(command.showId(), command.labels());
        if (resolved.size() != command.seatCount()) {
            return decline(command, recordId, ErrorCode.UNKNOWN_SEAT,
                    Map.of("unknownSeats", unknownLabels(command, resolved)));
        }

        quotas.ensure(command.showId(), command.userId(), show.get().getPerUserLimit());
        QuotaJdbcRepository.Quota quota = quotas
                .lockForUpdate(command.showId(), command.userId())
                .orElseThrow(() -> new IllegalStateException("quota row is missing after ensure"));
        if (quota.seatsHeld() + command.seatCount() > quota.seatLimit()) {
            return decline(command, recordId, ErrorCode.USER_LIMIT_EXCEEDED, Map.of(
                    "perUserLimit", quota.seatLimit(),
                    "seatsHeld", quota.seatsHeld(),
                    "seatsRequested", command.seatCount()));
        }

        List<Long> seatIds = resolved.stream().map(SeatRef::id).toList();
        List<SeatRow> locked = seats.lockByIds(command.showId(), seatIds);
        if (locked.size() != command.seatCount()) {
            throw new IllegalStateException("expected " + command.seatCount()
                    + " seats under lock but found " + locked.size());
        }
        List<String> unavailable = locked.stream()
                .filter(seat -> seat.status() != SeatStatus.AVAILABLE)
                .map(SeatRow::label)
                .toList();
        if (!unavailable.isEmpty()) {
            return decline(command, recordId, ErrorCode.SEAT_UNAVAILABLE,
                    Map.of("unavailableSeats", unavailable));
        }

        return confirm(command, recordId, locked, seatIds);
    }

    private ReserveOutcome confirm(ReserveCommand command, UUID recordId, List<SeatRow> locked,
            List<Long> seatIds) {
        long totalPaise = 0;
        for (SeatRow seat : locked) {
            totalPaise = Math.addExact(totalPaise, seat.pricePaise());
        }

        UUID reservationId = UUID.randomUUID();
        Instant createdAt = reservations.insertConfirmed(reservationId, command.showId(),
                command.userId(), command.seatCount(), totalPaise);
        reservations.insertLinks(reservationId, command.showId(), locked);

        int confirmed = seats.confirm(command.showId(), seatIds, reservationId);
        if (confirmed != command.seatCount()) {
            throw new IllegalStateException("expected to confirm " + command.seatCount()
                    + " seats but confirmed " + confirmed);
        }
        quotas.adjust(command.showId(), command.userId(), command.seatCount());

        String body = json(new ReservationResponse(reservationId, command.showId(),
                ReservationStatus.CONFIRMED, reservedSeats(locked), totalPaise, createdAt));
        idempotency.complete(recordId, 201, body);
        return new ReserveOutcome.Confirmed(body);
    }

    private ReserveOutcome decline(ReserveCommand command, UUID recordId, ErrorCode code,
            Map<String, Object> extensions) {
        String body = json(Problems.of(code, code.detail(), instanceOf(command), extensions));
        int status = code.status().value();
        idempotency.complete(recordId, status, body);
        return new ReserveOutcome.Declined(status, body);
    }

    private static List<ReservedSeat> reservedSeats(List<SeatRow> locked) {
        return locked.stream()
                .map(seat -> new ReservedSeat(seat.label(), seat.pricePaise()))
                .toList();
    }

    private static List<String> unknownLabels(ReserveCommand command, List<SeatRef> resolved) {
        List<String> found = resolved.stream().map(SeatRef::label).toList();
        return command.labels().stream().filter(label -> !found.contains(label)).toList();
    }

    private static String instanceOf(ReserveCommand command) {
        return "/shows/" + command.showId() + "/reserve";
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialize the response body", e);
        }
    }
}
