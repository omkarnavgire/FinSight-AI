package com.finsight.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/auth/login}.
 *
 * <p>Password is never logged or included in any response body.
 */
public record LoginRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid email address")
        String email,

        /** Plain-text password — never logged. */
        @NotBlank(message = "Password is required")
        String password
) {}
