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

@Component("validateEmail")
@RequiredArgsConstructor
public class ValidateEmailValidator implements BusinessValidator<User> {

    private final UserRepository repository;

    @Override
    public List<ValidationError> validate(User targetUser) {
        List<ValidationError> errors = new ArrayList<>();

        if (targetUser == null) {
            errors.add(new ValidationError("userObject", "User cannot null"));
            return errors;
        }

        if (Objects.isNull(targetUser.getEmail()) || targetUser.getEmail().trim().isBlank()) {
            errors.add(new ValidationError("email", "Email cannot be blank"));
        } else if (repository.existsByEmail(targetUser.getEmail())) {
            errors.add(new ValidationError("email", "Email taken: " + targetUser.getEmail()));
        }

        return errors;
    }

    @Override
    public Class<User> supports() {
        return User.class;
    }

    @Override
    public String getRuleName() {
        return "validateEmail";
    }
}
