package org.lukawska.springaop.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lukawska.springaop.dto.UserRequest;
import org.lukawska.springaop.dto.UserResponse;
import org.lukawska.springaop.entity.User;
import org.lukawska.springaop.exception.UserAlreadyExistsException;
import org.lukawska.springaop.exception.UserNotFoundException;
import org.lukawska.springaop.repository.UserRepository;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    @Test
    void shouldReturnUser_WhenGetUserById() {
        //given
        final Long id = 1L;
        User user = UserServiceTestData.createUser();
        UserResponse expected = UserServiceTestData.userResponse();

        //when
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        UserResponse actualUser = userService.getUserById(id);

        //then
        assertEquals(expected, actualUser);
    }

    @Test
    void shouldThrowNotFoundException_WhenGetUserById() {
        //given
        final Long id = 99L;

        //when
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        //then
        assertThrows(UserNotFoundException.class, () -> userService.getUserById(id));

    }

    @Test
    void shouldCreateUser_success() {
        //given
        UserRequest request = UserServiceTestData.userRequest();
        User user = UserServiceTestData.createUser();
        UserResponse expectedResponse = UserServiceTestData.userResponse();

        //when
        when(userRepository.save(any(User.class))).thenReturn(user);
        UserResponse actualResponse = userService.createUser(request);

        //then
        assertEquals(expectedResponse, actualResponse);
    }

    @Test
    void shouldThrowAlreadyExistsException_WhenCreateUser() {
        //given
        UserRequest request = UserServiceTestData.userRequest();

        //when
        when(userRepository.save(any(User.class)))
            .thenThrow(new DataIntegrityViolationException("Duplicate"));

        //then
        assertThrows(UserAlreadyExistsException.class, () -> userService.createUser(request));
    }

    @Test
    void shouldDeleteUserById_success() {
        //given
        final Long id = 1L;
        when(userRepository.existsById(id)).thenReturn(true);

        //when
        userService.deleteUserById(id);

        //then
        verify(userRepository, times(1)).existsById(id);
        verify(userRepository, times(1)).deleteById(id);
    }

    @Test
    void shouldThrowUserNotFoundException_WhenDeleteUserById() {
        //given
        final Long id = 1L;
        when(userRepository.existsById(id)).thenReturn(false);

        //when && then
        assertThrows(UserNotFoundException.class, () -> userService.deleteUserById(id));
        verify(userRepository, times(1)).existsById(id);
        verify(userRepository, times(0)).deleteById(id);
    }
}