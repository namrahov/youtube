package az.youtube.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class HeaderCurrentUserProviderTest {

    private final HeaderCurrentUserProvider provider = new HeaderCurrentUserProvider();

    @Test
    void returnsUserIdFromHeader() {
        assertThat(provider.requireUserId(requestWith(" 42 "))).isEqualTo(42L);
    }

    @Test
    void rejectsMissingHeader() {
        assertUnauthorized(new MockHttpServletRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "abc", "0", "-5", "1.5", "99999999999999999999"})
    void rejectsInvalidHeader(String value) {
        assertUnauthorized(requestWith(value));
    }

    private static MockHttpServletRequest requestWith(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HeaderCurrentUserProvider.HEADER, value);
        return request;
    }

    private void assertUnauthorized(MockHttpServletRequest request) {
        assertThatThrownBy(() -> provider.requireUserId(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
