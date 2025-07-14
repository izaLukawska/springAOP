package org.lukawska.springaop.controller;

import lombok.RequiredArgsConstructor;
import org.lukawska.springaop.dto.UserRequest;
import org.lukawska.springaop.dto.UserResponse;
import org.lukawska.springaop.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    /**
     * Method to handle the HTTP request to get the user by a specific ID.
     * @param id The ID number of the user we want to find.
     * @return The user's information, ready to be sent back (as a UserResponse).
     * @throws org.lukawska.springaop.exception.UserNotFoundException if no such user exists.
     */
    @GetMapping("/{id}")
    public UserResponse getUserById(@PathVariable Long id) {
        return userService.getUserById(id);
    }

    /**
     * Method to handle the HTTP request to create a new user.
     * It takes the user's data from the request body
     * @param userRequest The body of the request.
     * @return Status 201 (CREATED) with the body of the created user.
     * @throws org.lukawska.springaop.exception.UserAlreadyExistsException if such a user already exists.
     */
    @PostMapping
    public ResponseEntity<UserResponse> createUser(@RequestBody UserRequest userRequest) {
        return ResponseEntity.status(201).body(userService.createUser(userRequest));
    }

    /**
     * Method to handle HTTP request to delete a user by specific ID.
     * @param id The ID of a user we want to delete.
     * @throws org.lukawska.springaop.exception.UserNotFoundException if no such user exists.
     */
    @DeleteMapping("/{id}")
    public void deleteUserById(@PathVariable Long id) {
        userService.deleteUserById(id);
    }
}
