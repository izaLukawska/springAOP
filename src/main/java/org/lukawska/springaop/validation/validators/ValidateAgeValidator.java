package org.lukawska.springaop.validation.validators;

import org.lukawska.springaop.entity.User;
import org.lukawska.springaop.validation.BusinessValidator;
import org.lukawska.springaop.validation.ValidationError;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component("validateAge")
public class ValidateAgeValidator implements BusinessValidator<User> {

    @Override
    public List<ValidationError> validate(User targetUser) {
        List<ValidationError> errors = new ArrayList<>();

        if (targetUser == null) {
            errors.add(new ValidationError("userObject", "User cannot null"));
            return errors;
        }

        if (Objects.isNull(targetUser.getAge())) {
            errors.add(new ValidationError("age", "Age cannot be null"));
        } else if (targetUser.getAge() < 18) {
            errors.add(new ValidationError("age", "Age cannot be less than 18"));
        }

        return errors;
    }

    @Override
    public Class<User> supports() {
        return User.class;
    }

    @Override
    public String getRuleName() {
        return "validateAge";
    }
}
