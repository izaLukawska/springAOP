package org.lukawska.springaop.service;

import lombok.RequiredArgsConstructor;
import org.lukawska.springaop.dto.UserRequest;
import org.lukawska.springaop.dto.UserResponse;
import org.lukawska.springaop.entity.User;
import org.lukawska.springaop.exception.UserAlreadyExistsException;
import org.lukawska.springaop.exception.UserNotFoundException;
import org.lukawska.springaop.repository.UserRepository;
import org.lukawska.springaop.validation.BusinessValidation;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository repository;

    /**
     * Method used to retrieve User from the repository.
     * @param id The id of the user we want to retrieve.
     * @return User mapped to UserResponse (DTO)
     * @throws UserNotFoundException when no such user exists.
     */
    public UserResponse getUserById(Long id) {
        return mapToResponse(repository.findById(id).orElseThrow(() -> new UserNotFoundException(id)));
    }

    /**
     * Method to create a new user.
     * The method also applies BusinessValidation validation via the annotation @BusinessValidation.
     * @param request Take the request to map it to UserResponse.
     * @return Created User or UserAlreadyExistsException.
     * @throws UserAlreadyExistsException If a user with the same unique details (like username or email) already exists.
     */
    @BusinessValidation(rules = {"validateUsername", "validateEmail", "validateAge"}, failFast = false)
    public UserResponse createUser(UserRequest request) {
        try {
            return mapToResponse(repository.save(mapToUser(request)));
        } catch (DataIntegrityViolationException e) {
            throw new UserAlreadyExistsException();
        }
    }

    /**
     * Method to delete user.
     * Throws UserNotFoundException if no such user exists.
     * @param id The id of the user we want to delete.
     */
    public void deleteUserById(Long id) {
        if (!repository.existsById(id)) {
            throw new UserNotFoundException(id);
        }

        repository.deleteById(id);
    }

    private UserResponse mapToResponse(User user) {
        return new UserResponse(
            user.getId(),
            user.getUsername(),
            user.getEmail(),
            user.getAge());
    }

    private User mapToUser(UserRequest userRequest) {
        return User.builder()
            .username(userRequest.username())
            .email(userRequest.email())
            .age(userRequest.age())
            .build();
    }
}
