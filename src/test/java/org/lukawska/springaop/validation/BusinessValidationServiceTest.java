package org.lukawska.springaop.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lukawska.springaop.dto.UserRequest;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class BusinessValidationServiceTest {

    private BusinessValidationService validationService;

    @Mock
    private BusinessValidator<UserRequest> usernameValidator;

    @Mock
    private BusinessValidator<UserRequest> emailValidator;

    @Mock
    private BusinessValidator<UserRequest> ageValidator;

    private UserRequest validUser;

    @BeforeEach
    void setUp() {
        when(usernameValidator.getRuleName()).thenReturn("validateUsername");
        when(usernameValidator.supports()).thenReturn(UserRequest.class);

        when(emailValidator.getRuleName()).thenReturn("validateEmail");
        when(emailValidator.supports()).thenReturn(UserRequest.class);

        when(ageValidator.getRuleName()).thenReturn("validateAge");
        when(ageValidator.supports()).thenReturn(UserRequest.class);

        validationService = new BusinessValidationService(
            List.of(usernameValidator, emailValidator, ageValidator));

        validUser = new UserRequest("testUser", "example@example.com", 20);
    }

    @Test
    void shouldPassValidation_WhenAllRulesPass() {
        // given
        when(usernameValidator.validate(any(UserRequest.class))).thenReturn(Collections.emptyList());
        when(emailValidator.validate(any(UserRequest.class))).thenReturn(Collections.emptyList());
        when(ageValidator.validate(any(UserRequest.class))).thenReturn(Collections.emptyList());

        // when && then
        assertDoesNotThrow(() ->
            validationService.validate(validUser, true,
                new String[]{"validateUsername", "validateEmail", "validateAge"},
                "Validation failed")
        );
    }

    @Test
    void shouldCollectAllErrors_WhenFailFastIsFalse() {
        // given
        ValidationError usernameError = new ValidationError("username", "Username taken");
        ValidationError emailError = new ValidationError("email", "Invalid email format");
        ValidationError ageError = new ValidationError("age", "Age must be over 18");

        when(usernameValidator.validate(any(UserRequest.class))).thenReturn(List.of(usernameError));
        when(emailValidator.validate(any(UserRequest.class))).thenReturn(List.of(emailError));
        when(ageValidator.validate(any(UserRequest.class))).thenReturn(List.of(ageError));

        // when
        BusinessValidationException exception = assertThrows(
            BusinessValidationException.class,
            () -> validationService.validate(validUser, false,
                new String[]{"validateUsername", "validateEmail", "validateAge"},
                "Validation failed")
        );

        // then
        assertThat(exception.getErrors())
            .hasSize(3)
            .containsExactlyInAnyOrder(usernameError, emailError, ageError);

        verify(usernameValidator).validate(validUser);
        verify(emailValidator).validate(validUser);
        verify(ageValidator).validate(validUser);
    }

    @Test
    void shouldStopOnFirstError_WhenFailFastIsTrue() {
        // given
        ValidationError usernameError = new ValidationError("username", "Username taken");
        when(usernameValidator.validate(any(UserRequest.class))).thenReturn(List.of(usernameError));

        // when
        BusinessValidationException exception = assertThrows(
            BusinessValidationException.class,
            () -> validationService.validate(validUser, true,
                new String[]{"validateUsername", "validateEmail", "validateAge"},
                "Validation failed")
        );

        // then
        assertThat(exception.getErrors())
            .hasSize(1)
            .containsExactly(usernameError);

        verify(usernameValidator).validate(validUser);
        verify(emailValidator, never()).validate(any());
        verify(ageValidator, never()).validate(any());
    }

    @Test
    void shouldValidateOnlySpecifiedRules() {
        // given
        when(emailValidator.validate(any(UserRequest.class))).thenReturn(Collections.emptyList());
        when(ageValidator.validate(any(UserRequest.class))).thenReturn(Collections.emptyList());

        // when && then
        assertDoesNotThrow(() -> validationService.validate(validUser, true,
            new String[]{"validateEmail", "validateAge"}, "Validation failed"));

        verify(usernameValidator, never()).validate(any());
        verify(emailValidator).validate(validUser);
        verify(ageValidator).validate(validUser);
    }

    @Test
    void shouldHandleNullTarget() {
        // when && then
        assertDoesNotThrow(() -> validationService.validate(null, true,
                new String[]{"validateUsername", "validateEmail", "validateAge"},
                "Validation failed")
        );

        verify(usernameValidator, never()).validate(any());
        verify(emailValidator, never()).validate(any());
        verify(ageValidator, never()).validate(any());
    }

    @Test
    void shouldHandleEmptyRuleNames() {
        // given
        when(usernameValidator.validate(any(UserRequest.class))).thenReturn(Collections.emptyList());
        when(emailValidator.validate(any(UserRequest.class))).thenReturn(Collections.emptyList());
        when(ageValidator.validate(any(UserRequest.class))).thenReturn(Collections.emptyList());

        // when & &then
        assertDoesNotThrow(() -> validationService.validate(
            validUser,
            true,
            new String[]{},
            "Validation failed")
        );

        verify(usernameValidator).validate(validUser);
        verify(emailValidator).validate(validUser);
        verify(ageValidator).validate(validUser);
    }

}
