package org.lukawska.springaop.validation.validators;

import lombok.RequiredArgsConstructor;
import org.lukawska.springaop.dto.UserRequest;
import org.lukawska.springaop.repository.UserRepository;
import org.lukawska.springaop.validation.BusinessValidator;
import org.lukawska.springaop.validation.exception.ValidationError;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component("validateUsername")
@RequiredArgsConstructor
public class ValidateUsernameValidator implements BusinessValidator<UserRequest> {

    private final UserRepository userRepository;

    @Override
    public List<ValidationError> validate(UserRequest targetUser) {
        List<ValidationError> errors = new ArrayList<>();

        if (targetUser == null) {
            errors.add(new ValidationError("userObject", "User object cannot be null."));
            return errors;
        }

        if (Objects.isNull(targetUser.username()) || targetUser.username().trim().isEmpty()) {
            errors.add(new ValidationError("username", "Username must not be blank."));
        } else if (userRepository.existsByUsername(targetUser.username())) {
            errors.add(new ValidationError("username", "Username taken: " + targetUser.username()));
        }

        return errors;
    }


    @Override
    public Class<UserRequest> supports() {
        return UserRequest.class;
    }

    @Override
    public String getRuleName() {
        return "validateUsername";
    }
}
