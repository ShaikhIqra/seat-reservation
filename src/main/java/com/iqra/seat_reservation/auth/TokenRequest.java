package com.iqra.seat_reservation.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TokenRequest(
        @NotBlank @Size(max = 100) String userId,
        String role          // "admin" or null/"user"
) {}
