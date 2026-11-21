package com.finsight.shared;

import com.finsight.shared.advice.GlobalExceptionHandler;
import com.finsight.shared.exception.AccountNotFoundException;
import com.finsight.shared.exception.BudgetNotFoundException;
import com.finsight.shared.exception.FraudAlertNotFoundException;
import com.finsight.shared.exception.TransactionNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link GlobalExceptionHandler} (T-59): RFC 7807 statuses and no internal detail leaks. */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("Every domain NotFoundException subclass maps to 404")
    void notFoundSubclasses_404() {
        for (var ex : new RuntimeException[]{new AccountNotFoundException(1L), new BudgetNotFoundException(1L),
                new TransactionNotFoundException(1L), new FraudAlertNotFoundException(1L)}) {
            var response = handler.handleNotFound((com.finsight.shared.exception.NotFoundException) ex, null);
            assertThat(response.getStatusCode()).as(ex.getClass().getSimpleName()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody().getTitle()).isEqualTo("Not Found");
        }
    }

    @Test
    @DisplayName("A wrong parameter type is 400 naming the parameter, never echoing the value")
    void typeMismatch_400() throws Exception {
        var param = new MethodParameter(Object.class.getMethod("equals", Object.class), 0);
        var ex = new MethodArgumentTypeMismatchException("<script>", Long.class, "id", param, null);

        var response = handler.handleTypeMismatch(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getDetail()).contains("'id'").doesNotContain("<script>");
    }

    @Test
    @DisplayName("A malformed body is 400 without parser details")
    void unreadable_400() {
        var ex = new HttpMessageNotReadableException("Unexpected token at line 3 column 7",
                new MockHttpInputMessage(new byte[0]));

        var response = handler.handleUnreadable(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getDetail()).doesNotContain("line 3");
    }

    @Test
    @DisplayName("Access denied stays 403 and is never turned into a 500")
    void accessDenied_403() {
        assertThat(handler.handleAccessDenied(new AccessDeniedException("x")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("A Spring authentication failure is 401 with a generic message")
    void springAuthentication_401() {
        var response = handler.handleSpringAuthentication(new BadCredentialsException("user secret@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().getDetail()).doesNotContain("secret@example.com");
    }

    @Test
    @DisplayName("Spring MVC errors keep their own status: 404 unknown route, 405 wrong method, ResponseStatusException")
    void errorResponses_keepStatus() {
        assertThat(handler.handleUnexpected(new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/x", "x"))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var methodNotAllowed = handler.handleUnexpected(new HttpRequestMethodNotSupportedException("DELETE"));
        assertThat(methodNotAllowed.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handler.handleUnexpected(new ResponseStatusException(HttpStatus.CONFLICT)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("Any other exception is a 500 with a fixed message; the cause is not returned")
    void unexpected_500_noLeak() {
        var response = handler.handleUnexpected(new IllegalStateException("db password=hunter2 at Foo.java:42"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getDetail()).isEqualTo("An unexpected error occurred");
        assertThat(response.getBody().toString()).doesNotContain("hunter2").doesNotContain("Foo.java");
    }
}
