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

@Component("validateEmail")
@RequiredArgsConstructor
public class ValidateEmailValidator implements BusinessValidator<UserRequest> {

    private final UserRepository repository;

    @Override
    public List<ValidationError> validate(UserRequest targetUser) {
        List<ValidationError> errors = new ArrayList<>();

        if (targetUser == null) {
            errors.add(new ValidationError("userObject", "User cannot null"));
            return errors;
        }

        if (Objects.isNull(targetUser.email()) || targetUser.email().trim().isBlank()) {
            errors.add(new ValidationError("email", "Email cannot be blank"));
        } else if (repository.existsByEmail(targetUser.email())) {
            errors.add(new ValidationError("email", "Email taken: " + targetUser.email()));
        }

        return errors;
    }

    @Override
    public Class<UserRequest> supports() {
        return UserRequest.class;
    }

    @Override
    public String getRuleName() {
        return "validateEmail";
    }
}
