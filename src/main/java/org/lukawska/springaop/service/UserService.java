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

    @DistributedLock(
        lockName = "user_lookup",
        key = "#id",
        waitTimeSeconds = 5,
        leaseTimeSeconds = -1,
        strategy = LockStrategy.WAIT_AND_RETRY,
        fallbackMethod = "fallbackGetUserById"
    )
    public UserResponse getUserById(Long id) {
        return mapToResponse(repository.findById(id).orElseThrow(() -> new UserNotFoundException(id)));
    }

    public UserResponse fallbackGetUserById(Long id){
        log.info("Fallback: Could not acquire lock for getUserById({}).", id);
        throw new RuntimeException("Service unavailable due to locking issues for user ID: " + id);
    }

    public UserResponse createUser(UserRequest request){
        try {
            return mapToResponse(repository.save(mapToUser(request)));
        } catch (DataIntegrityViolationException e){
            throw new UserAlreadyExistsException();
        }
    }

    public void deleteUserById(Long id){
        if(!repository.existsById(id)){
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
