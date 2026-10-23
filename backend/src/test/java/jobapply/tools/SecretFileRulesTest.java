package jobapply.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretFileRulesTest {

    @Test
    void secretNamesAreRecognisedCaseInsensitively() {
        for (String name : new String[] {".env", ".env.local", ".ENV.production", "server.pem", "tls.key", "cert.p12",
                "cert.pfx", "id_rsa", "id_rsa.pub", "id_ed25519", "aws-credentials.json", "client_secret.json",
                "Secrets.yaml", ".npmrc", ".pypirc", ".netrc", "release.keystore", "app.jks"}) {
            assertThat(SecretFileRules.isSecret(name)).as(name).isTrue();
        }
        for (String name : new String[] {"README.md", "environment.md", "keyboard.txt", "pom.xml", "env.example.md"}) {
            assertThat(SecretFileRules.isSecret(name)).as(name).isFalse();
        }
    }

    @Test
    void directoriesAndPaths() {
        assertThat(SecretFileRules.skippedDirectory(".git")).contains(SecretFileRules.GIT);
        assertThat(SecretFileRules.skippedDirectory("node_modules")).contains(SecretFileRules.GENERATED);
        assertThat(SecretFileRules.skippedDirectory("secrets")).contains(SecretFileRules.SECRET);
        assertThat(SecretFileRules.skippedDirectory("src")).isEmpty();
        assertThat(SecretFileRules.skippedPath("a/node_modules/b/package.json")).contains(SecretFileRules.GENERATED);
        assertThat(SecretFileRules.skippedPath("config/.env")).contains(SecretFileRules.SECRET);
        assertThat(SecretFileRules.skippedPath("docs/README.md")).isEmpty();
    }
}
