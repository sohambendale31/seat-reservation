package com.seatres.web.dto;

import com.seatres.domain.SeatStatus;

public record SeatView(String label, SeatStatus status, long pricePaise) {
}
