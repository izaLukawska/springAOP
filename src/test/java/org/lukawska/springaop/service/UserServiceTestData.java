package org.lukawska.springaop.service;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.lukawska.springaop.dto.UserRequest;
import org.lukawska.springaop.dto.UserResponse;
import org.lukawska.springaop.entity.User;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class UserServiceTestData {

    public static User createUser(){
        return User.builder()
            .id(1L)
            .username("test")
            .email("example@example.com")
            .age(20)
            .build();
    }

    public static UserRequest userRequest(){
        return new UserRequest("test", "example@example.com", 20);
    }

    public static UserResponse userResponse(){
        return new UserResponse(1L, "test", "example@example.com", 20);
    }
}
