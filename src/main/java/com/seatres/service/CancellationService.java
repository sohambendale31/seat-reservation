package com.seatres.service;

import com.seatres.domain.ReservationStatus;
import com.seatres.error.ReservationNotFoundException;
import com.seatres.repository.QuotaJdbcRepository;
import com.seatres.repository.ReservationJdbcRepository;
import com.seatres.repository.ReservationJdbcRepository.Owner;
import com.seatres.repository.ReservationJdbcRepository.State;
import com.seatres.repository.SeatJdbcRepository;
import com.seatres.service.tx.TxExecutor;
import com.seatres.web.dto.ReservationResponse;
import com.seatres.web.dto.ReservedSeat;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The cancel transaction. Locks follow the same global order as reserve: quota row, then the
 * reservation, then its seat rows by ascending id.
 *
 * <p>Releases are predicated on {@code reservation_id = :id}, so a seat that was already released
 * and taken by someone else can never be pulled back.
 */
@Service
public class CancellationService {

    private final TxExecutor tx;
    private final ReservationJdbcRepository reservations;
    private final SeatJdbcRepository seats;
    private final QuotaJdbcRepository quotas;

    public CancellationService(TxExecutor tx, ReservationJdbcRepository reservations,
            SeatJdbcRepository seats, QuotaJdbcRepository quotas) {
        this.tx = tx;
        this.reservations = reservations;
        this.seats = seats;
        this.quotas = quotas;
    }

    public ReservationResponse cancel(UUID reservationId, String callerId) {
        return tx.execute(context -> {
            Owner owner = reservations.findOwner(reservationId)
                    .filter(found -> found.userId().equals(callerId))
                    .orElseThrow(() -> new ReservationNotFoundException(reservationId));

            quotas.lockForUpdate(owner.showId(), callerId).orElseThrow(() ->
                    new IllegalStateException("no quota row for an existing reservation"));

            State state = reservations.lockForUpdate(reservationId).orElseThrow(() ->
                    new IllegalStateException("reservation disappeared while being locked"));

            if (state.status() == ReservationStatus.CANCELLED) {
                return response(reservationId, owner, state, state.cancelledAt());
            }

            List<Long> lockedSeats = seats.lockByReservation(reservationId);
            assertCount(lockedSeats.size(), state.seatCount(), "lock");
            assertCount(seats.release(reservationId), state.seatCount(), "release");
            assertCount(reservations.releaseLinks(reservationId), state.seatCount(), "unlink");

            Instant cancelledAt = reservations.markCancelled(reservationId);
            quotas.adjust(owner.showId(), callerId, -state.seatCount());

            return response(reservationId, owner, state, cancelledAt);
        });
    }

    private ReservationResponse response(UUID reservationId, Owner owner, State state,
            Instant cancelledAt) {
        List<ReservedSeat> reserved = reservations.findReservedSeats(reservationId);
        return new ReservationResponse(reservationId, owner.showId(), ReservationStatus.CANCELLED,
                reserved, state.totalPaise(), state.createdAt(), cancelledAt);
    }

    private static void assertCount(int actual, int expected, String step) {
        if (actual != expected) {
            throw new IllegalStateException(
                    "expected to " + step + " " + expected + " seats but affected " + actual);
        }
    }
}
