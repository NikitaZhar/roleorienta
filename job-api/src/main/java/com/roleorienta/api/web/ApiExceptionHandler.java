package com.roleorienta.api.web;

import com.roleorienta.api.account.EmailTakenException;
import com.roleorienta.api.condition.ConditionVersionException;
import com.roleorienta.api.condition.InvalidFieldException;
import com.roleorienta.api.vacancy.InvalidCursorException;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Ошибки API в формате {@code application/problem+json} (RFC 9457, технический документ §8).
 * Стандартные ошибки Spring MVC — базовым классом; ошибки полей — расширением {@code errors}:
 * список {@code {"pointer": "/поле", "detail": "…"}}.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("pointer", pointer(error.getField()),
                        "detail", String.valueOf(error.getDefaultMessage())))
                .toList();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request fields");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * @param exception недопустимое значение поля
     * @return {@code 400} с ошибкой поля
     */
    @ExceptionHandler(InvalidFieldException.class)
    public ProblemDetail invalidField(InvalidFieldException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request fields");
        problem.setProperty("errors", List.of(Map.of("pointer", exception.getPointer(),
                "detail", exception.getMessage())));
        return problem;
    }

    /**
     * @param exception курсор страницы испорчен или от другого списка
     * @return {@code 400}
     */
    @ExceptionHandler(InvalidCursorException.class)
    public ProblemDetail invalidCursor(InvalidCursorException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    /**
     * @param exception email уже зарегистрирован
     * @return {@code 409}
     */
    @ExceptionHandler(EmailTakenException.class)
    public ProblemDetail emailTaken(EmailTakenException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    /**
     * @param exception версия условий не та ({@code 412}) или не указана ({@code 428})
     * @return {@code 412} или {@code 428}
     */
    @ExceptionHandler(ConditionVersionException.class)
    public ProblemDetail conditionVersion(ConditionVersionException exception) {
        return ProblemDetail.forStatusAndDetail(exception.isMissing() ? HttpStatus.PRECONDITION_REQUIRED
                : HttpStatus.PRECONDITION_FAILED, exception.getMessage());
    }

    /**
     * @param exception неверный email или пароль при входе
     * @return {@code 401}
     */
    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail authentication(AuthenticationException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid email or password");
    }

    /**
     * Нарушено ограничение БД при одновременных запросах (например, две первые записи условий).
     *
     * @param exception нарушение ограничения
     * @return {@code 409}
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail conflict(DataIntegrityViolationException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Concurrent change, repeat the request");
    }

    /**
     * Путь поля привязки ({@code countries[0]}) → JSON Pointer ({@code /countries/0}).
     */
    private static String pointer(String field) {
        return "/" + field.replace("].", "/").replace("[", "/").replace("]", "").replace('.', '/');
    }
}
