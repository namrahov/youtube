package az.youtube.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SearchQueryTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void combinesFuzzyAndPrefixTextMatchWithPopularityAndFreshness() {
        String body = json.writeValueAsString(SearchQuery.build("spring kafak", 20, 10));

        assertThat(body)
                .contains("\"from\":20", "\"size\":10")
                .contains("\"title\":{", "\"fuzziness\":\"AUTO\"", "\"operator\":\"and\"")
                .contains("\"title.prefix\":{")
                .contains("\"field_value_factor\":{", "\"modifier\":\"log2p\"")
                .contains("\"gauss\":{\"publishedAt\":{")
                .contains("\"score_mode\":\"sum\"", "\"boost_mode\":\"multiply\"");
    }

    @Test
    void userInputStaysAStringValue() {
        String body = json.writeValueAsString(SearchQuery.build("\"}},{\"match_all\":{}", 0, 10));

        assertThat(body).doesNotContain("\"match_all\":{}").contains("\\\"}},{\\\"match_all\\\":{}");
    }
}
