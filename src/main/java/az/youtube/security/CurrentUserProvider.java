package az.youtube.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves who is making the request. Business code never depends on how this is done,
 * so the header-based implementation can later be replaced by Spring Security / JWT.
 */
public interface CurrentUserProvider {

    /**
     * @return the current user's id
     * @throws org.springframework.web.server.ResponseStatusException with 401 if the request is not authenticated
     */
    long requireUserId(HttpServletRequest request);
}
