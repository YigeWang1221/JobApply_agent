package jobapply.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ModelSettingsServiceTest {

    @Test
    void sameEndpointIgnoresOnlyWritingDifferences() {
        assertThat(ModelSettingsService.sameEndpoint("https://api.example.com/v1", "https://api.example.com/v1/"))
                .isTrue();
        assertThat(ModelSettingsService.sameEndpoint("https://API.Example.com/v1", "https://api.example.com/v1"))
                .isTrue();
        assertThat(ModelSettingsService.sameEndpoint("https://api.example.com:443/v1", "https://api.example.com/v1"))
                .isTrue();
        assertThat(ModelSettingsService.sameEndpoint("http://127.0.0.1:80/v1", "http://127.0.0.1/v1")).isTrue();
        assertThat(ModelSettingsService.sameEndpoint(" ${BASE_URL} ", "${BASE_URL}/")).isTrue();

        assertThat(ModelSettingsService.sameEndpoint("https://api.example.com/v1", "https://api.example.com/v2"))
                .isFalse();
        assertThat(ModelSettingsService.sameEndpoint("https://api.example.com/v1", "http://api.example.com/v1"))
                .isFalse();
        assertThat(ModelSettingsService.sameEndpoint("http://127.0.0.1:8080/v1", "http://127.0.0.1:9090/v1"))
                .isFalse();
        assertThat(ModelSettingsService.sameEndpoint("https://a.example.com/v1", "https://b.example.com/v1"))
                .isFalse();
        assertThat(ModelSettingsService.sameEndpoint("${BASE_URL}", "${OTHER_URL}")).isFalse();
    }
}
