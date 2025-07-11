package org.lukawska.springaop.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lukawska.springaop.dto.UserRequest;
import org.lukawska.springaop.dto.UserResponse;
import org.lukawska.springaop.service.UserService;
import org.lukawska.springaop.versioning.ApiVersion;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
@Slf4j
public class UserController {

    private final UserService userService;

    @ApiVersion(versions = {"v1", "v2"}, since = "v1", deprecated = "v1")
    @GetMapping(path = "/{id}")
    public UserResponse getUserById(@PathVariable Long id) {
        return userService.getUserById(id);
    }

    @ApiVersion(versions = {"v1", "v2"}, since = "v1", requiresVersion = false)
    @PostMapping()
    public ResponseEntity<UserResponse> createUser(@RequestBody UserRequest userRequest) {
        return ResponseEntity.status(201).body(userService.createUser(userRequest));
    }

    @DeleteMapping("/{id}")
    public void deleteUserById(@PathVariable Long id) {
        userService.deleteUserById(id);
    }
}
