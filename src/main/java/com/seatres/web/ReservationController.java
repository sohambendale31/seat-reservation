package com.seatres.web;

import com.seatres.domain.ReserveCommand;
import com.seatres.domain.ReserveOutcome;
import com.seatres.error.ApiException;
import com.seatres.error.ErrorCode;
import com.seatres.service.CancellationService;
import com.seatres.service.RequestFingerprinter;
import com.seatres.service.ReservationService;
import com.seatres.web.dto.ReservationResponse;
import com.seatres.web.dto.ReserveRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Reservations")
public class ReservationController {

    static final String KEY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_.:-]{1,128}$");

    private final ReservationService reservationService;
    private final CancellationService cancellationService;
    private final RequestFingerprinter fingerprinter;

    public ReservationController(ReservationService reservationService,
            CancellationService cancellationService, RequestFingerprinter fingerprinter) {
        this.reservationService = reservationService;
        this.cancellationService = cancellationService;
        this.fingerprinter = fingerprinter;
    }

    @Operation(summary = "Book seats for a show",
            description = "Books 1-10 seats at once: you get all of them or none. Send a fresh "
                    + "Idempotency-Key per booking attempt; if the call fails or times out, "
                    + "repeat it with the same key and you will get the original answer back "
                    + "rather than a second booking.")
    @ApiResponse(responseCode = "201", description = "The seats are confirmed",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ReservationResponse.class)))
    @PostMapping(path = "/shows/{showId}/reserve", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> reserve(@PathVariable UUID showId,
            @Parameter(required = true, description = "Unique per logical attempt; a UUID is ideal",
                    example = "3f2b1c9e-7a4d-4f51-9a1f-0f3f3c1c2d10")
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

    /** No consumes and no body parameter, so any body and any content type are ignored. */
    @Operation(summary = "Cancel a booking and release its seats",
            description = "Frees exactly the seats in this booking, so anyone can take them again. "
                    + "Cancelling twice is harmless. You can only cancel your own booking.")
    @PostMapping("/reservations/{reservationId}/cancel")
    ReservationResponse cancel(@PathVariable UUID reservationId,
            @AuthenticationPrincipal Jwt jwt) {
        return cancellationService.cancel(reservationId, jwt.getSubject());
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
