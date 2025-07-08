package org.lukawska.springaop.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UserRequest(

    @NotBlank(message = "Username must not be blank")
    String username,

    @Email(message = "Invalid email format")
    @NotBlank(message = "Email must not be blank")
    String email,

    @NotNull(message = "Age must not be null")
    @Min(value = 18, message = "Age must be at least 18")
    Integer age
) {}
