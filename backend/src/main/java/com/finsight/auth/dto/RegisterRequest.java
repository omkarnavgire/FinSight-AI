package com.finsight.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/register}.
 *
 * <p>Validated before the service layer is invoked.
 * Password is never logged — the field is intentionally named {@code password}
 * (not {@code passwordHash}) and is handled only inside {@code AuthService}.
 */
public record RegisterRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid email address")
        @Size(max = 255, message = "Email must not exceed 255 characters")
        String email,

        /**
         * Plain-text password supplied by the user.
         * BCrypt-hashed before persistence; never logged.
         */
        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        String password,

        @NotBlank(message = "Full name is required")
        @Size(max = 100, message = "Full name must not exceed 100 characters")
        String fullName
) {}
