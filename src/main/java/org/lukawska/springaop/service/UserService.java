package org.lukawska.springaop.service;

import lombok.RequiredArgsConstructor;
import org.lukawska.springaop.cache.InvalidateCache;
import org.lukawska.springaop.cache.SmartCache;
import org.lukawska.springaop.dto.UserRequest;
import org.lukawska.springaop.dto.UserResponse;
import org.lukawska.springaop.entity.User;
import org.lukawska.springaop.exception.UserAlreadyExistsException;
import org.lukawska.springaop.exception.UserNotFoundException;
import org.lukawska.springaop.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository repository;

    @SmartCache(cacheName = "users", key = "#id", ttlSeconds = 3600, useWeakReference = true)
    public UserResponse getUserById(Long id) {
        return mapToResponse(repository.findById(id).orElseThrow(() -> new UserNotFoundException(id)));
    }

    @InvalidateCache(cacheNames = {"users"})
    public UserResponse createUser(UserRequest request) {
        try {
            return mapToResponse(repository.save(mapToUser(request)));
        } catch (DataIntegrityViolationException e) {
            throw new UserAlreadyExistsException();
        }
    }

    @InvalidateCache(cacheNames = {"users"})
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
