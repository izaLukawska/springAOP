package org.lukawska.springaop.validation;

import java.util.List;

public interface BusinessValidator<T> {

    List<ValidationError> validate(T target);

    Class<T> supports();

    String getRuleName();

}
