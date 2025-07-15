package org.lukawska.springaop.dto;

import java.io.Serializable;

public record UserResponse(Long id, String username, String email, Integer age) implements Serializable {}
