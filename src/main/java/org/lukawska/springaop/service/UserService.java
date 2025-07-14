package org.lukawska.springaop.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lukawska.springaop.dto.UserRequest;
import org.lukawska.springaop.dto.UserResponse;
import org.lukawska.springaop.entity.User;
import org.lukawska.springaop.exception.UserAlreadyExistsException;
import org.lukawska.springaop.exception.UserNotFoundException;
import org.lukawska.springaop.locking.DistributedLock;
import org.lukawska.springaop.locking.LockStrategy;
import org.lukawska.springaop.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository repository;

    public UserResponse getUserById(Long id) {
        return mapToResponse(repository.findById(id).orElseThrow(() -> new UserNotFoundException(id)));
    }

    @DistributedLock(
        lockName = "user_lookup",
        key = "#request.username",
        waitTimeSeconds = 3,
        leaseTimeSeconds = 10,
        strategy = LockStrategy.WAIT_AND_RETRY,
        fallbackMethod = "createUserFallback"
    )
    public UserResponse createUser(UserRequest request) {
        try {
            Thread.sleep(5000);
            return mapToResponse(repository.save(mapToUser(request)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (DataIntegrityViolationException e) {
            throw new UserAlreadyExistsException();
        }
    }

    public UserResponse createUserFallback(UserRequest request) {
        return new UserResponse(99L, "fallback", "test@test.com", 30);
    }

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
