package org.lukawska.springaop.validation.validators;

import org.lukawska.springaop.dto.UserRequest;
import org.lukawska.springaop.validation.BusinessValidator;
import org.lukawska.springaop.validation.exception.ValidationError;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component("validateAge")
public class ValidateAgeValidator implements BusinessValidator<UserRequest> {

    @Override
    public List<ValidationError> validate(UserRequest targetUser) {
        List<ValidationError> errors = new ArrayList<>();

        if (targetUser == null) {
            errors.add(new ValidationError("userObject", "User cannot null"));
            return errors;
        }

        if (Objects.isNull(targetUser.age())) {
            errors.add(new ValidationError("age", "Age cannot be null"));
        } else if (targetUser.age() < 18) {
            errors.add(new ValidationError("age", "Age cannot be less than 18"));
        }

        return errors;
    }

    @Override
    public Class<UserRequest> supports() {
        return UserRequest.class;
    }

    @Override
    public String getRuleName() {
        return "validateAge";
    }
}
