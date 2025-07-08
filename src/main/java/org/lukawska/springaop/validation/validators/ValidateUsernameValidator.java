package org.lukawska.springaop.validation.validators;

import lombok.RequiredArgsConstructor;
import org.lukawska.springaop.entity.User;
import org.lukawska.springaop.repository.UserRepository;
import org.lukawska.springaop.validation.BusinessValidator;
import org.lukawska.springaop.validation.ValidationError;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component("validateUsername")
@RequiredArgsConstructor
public class ValidateUsernameValidator implements BusinessValidator<User> {

    private final UserRepository userRepository;

    @Override
    public List<ValidationError> validate(User targetUser) {
        List<ValidationError> errors = new ArrayList<>();

        if (targetUser == null) {
            errors.add(new ValidationError("userObject", "User object cannot be null."));
            return errors;
        }

        if (Objects.isNull(targetUser.getUsername()) || targetUser.getUsername().trim().isEmpty()) {
            errors.add(new ValidationError("username", "Username must not be blank."));
        } else if (userRepository.existsByUsername(targetUser.getUsername())) {
            errors.add(new ValidationError("username", "Username taken: " + targetUser.getUsername()));
        }

        return errors;
    }


    @Override
    public Class<User> supports() {
        return User.class;
    }

    @Override
    public String getRuleName() {
        return "validateUsername";
    }
}
