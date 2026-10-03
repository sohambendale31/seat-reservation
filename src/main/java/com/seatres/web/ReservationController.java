package com.seatres.web;

import com.seatres.domain.ReserveCommand;
import com.seatres.domain.ReserveOutcome;
import com.seatres.error.ApiException;
import com.seatres.error.ErrorCode;
import com.seatres.service.RequestFingerprinter;
import com.seatres.service.ReservationService;
import com.seatres.web.dto.ReserveRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReservationController {

    static final String KEY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_.:-]{1,128}$");

    private final ReservationService reservationService;
    private final RequestFingerprinter fingerprinter;

    public ReservationController(ReservationService reservationService,
            RequestFingerprinter fingerprinter) {
        this.reservationService = reservationService;
        this.fingerprinter = fingerprinter;
    }

    @PostMapping(path = "/shows/{showId}/reserve", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> reserve(@PathVariable UUID showId,
            @RequestHeader(name = KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody ReserveRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String key = acceptedKey(idempotencyKey);
        List<String> labels = request.sortedSeats();
        ReserveCommand command = new ReserveCommand(showId, jwt.getSubject(), labels, key,
                fingerprinter.reserve(showId, labels));

        ReserveOutcome outcome = reservationService.reserve(command);
        return respond(outcome, outcome instanceof ReserveOutcome.Replayed);
    }

    /** The body is already bound and validated by now, so a bad body is reported before a bad key. */
    private static String acceptedKey(String supplied) {
        if (supplied == null || supplied.isBlank()) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_MISSING);
        }
        if (!KEY_PATTERN.matcher(supplied).matches()) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_INVALID);
        }
        return supplied;
    }

    private static ResponseEntity<String> respond(ReserveOutcome outcome, boolean replayed) {
        MediaType contentType = outcome.status() < 300
                ? MediaType.APPLICATION_JSON
                : MediaType.APPLICATION_PROBLEM_JSON;
        ResponseEntity.BodyBuilder response = ResponseEntity.status(outcome.status())
                .contentType(contentType);
        if (replayed) {
            response.header(REPLAYED_HEADER, "true");
        }
        return response.body(outcome.body());
    }
}
