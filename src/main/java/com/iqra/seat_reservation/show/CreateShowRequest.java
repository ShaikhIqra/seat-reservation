package com.iqra.seat_reservation.show;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.List;
import java.util.UUID;

public record CreateShowRequest(
        @NotBlank String name,
        @NotEmpty List<@NotBlank String> seats,
        @NotNull @PositiveOrZero Long pricePaise,
        @Positive Integer perUserLimit      // optional, default 4
) {}

