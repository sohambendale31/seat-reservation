package com.seatres.web;

import com.seatres.service.ShowService;
import com.seatres.web.dto.CreateShowRequest;
import com.seatres.web.dto.ShowDetailsResponse;
import com.seatres.web.dto.ShowResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Shows")
public class ShowController {

    private final ShowService showService;

    public ShowController(ShowService showService) {
        this.showService = showService;
    }

    @Operation(summary = "Create a show and its seat map",
            description = "Admin only. Give it rows of seats and a price per row, and every seat "
                    + "is created as available.")
    @PostMapping(path = "/shows", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ShowResponse> create(@Valid @RequestBody CreateShowRequest request) {
        ShowResponse show = showService.create(request);
        return ResponseEntity.created(URI.create("/shows/" + show.id())).body(show);
    }

    @Operation(summary = "See a show's seats, prices and availability",
            description = "Returns every seat with its price and whether it is free or taken, plus "
                    + "a tally. The tally always adds up to the seat list, never a stale count.")
    @GetMapping("/shows/{showId}")
    ShowDetailsResponse details(@PathVariable UUID showId) {
        return showService.details(showId);
    }
}
