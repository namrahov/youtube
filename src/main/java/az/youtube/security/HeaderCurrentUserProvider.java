package az.youtube.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Development-only authentication: trusts the {@value #HEADER} header.
 * Mirrors the API-gateway pattern where the gateway validates the token and forwards the user id.
 */
@Component
public class HeaderCurrentUserProvider implements CurrentUserProvider {

    public static final String HEADER = "X-User-Id";

    @Override
    public long requireUserId(HttpServletRequest request) {
        String value = request.getHeader(HEADER);
        if (value == null || value.isBlank()) {
            throw unauthorized("Missing " + HEADER + " header");
        }
        long userId;
        try {
            userId = Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            throw unauthorized("Invalid " + HEADER + " header");
        }
        if (userId <= 0) {
            throw unauthorized("Invalid " + HEADER + " header");
        }
        return userId;
    }

    private static ResponseStatusException unauthorized(String reason) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, reason);
    }
}
