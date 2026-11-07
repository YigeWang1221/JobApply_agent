package jobapply.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** C028：自己写的 id_token 校验（RS256）对得上，改了 nonce / aud / 签名都拒绝。 */
class ChatGptIdTokenTest {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    @Test
    void verifiesSignatureAndClaims() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        RSAPublicKey pub = (RSAPublicKey) pair.getPublic();
        JsonNode jwks = JsonMapper.builder().build().readTree("{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"k1\",\"n\":\""
                + B64.encodeToString(unsigned(pub.getModulus().toByteArray())) + "\",\"e\":\""
                + B64.encodeToString(unsigned(pub.getPublicExponent().toByteArray())) + "\"}]}");
        long exp = Instant.now().getEpochSecond() + 600;
        String claims = "{\"iss\":\"https://auth.openai.com\",\"aud\":[\"oaiapp_x\"],\"exp\":" + exp
                + ",\"nonce\":\"n1\",\"sub\":\"user-1\",\"email\":\"a@example.com\"}";
        String token = sign(pair, claims);

        assertThat(ChatGptIdToken.verify(token, jwks, "oaiapp_x", "n1", Instant.now()))
                .isEqualTo(new ChatGptIdToken.Claims("user-1", "a@example.com"));
        assertThatThrownBy(() -> ChatGptIdToken.verify(token, jwks, "oaiapp_x", "other", Instant.now()))
                .isInstanceOf(ChatGptIdToken.Invalid.class);
        assertThatThrownBy(() -> ChatGptIdToken.verify(token, jwks, "oaiapp_y", "n1", Instant.now()))
                .isInstanceOf(ChatGptIdToken.Invalid.class);
        String tampered = token.substring(0, token.lastIndexOf('.') + 1) + B64.encodeToString(new byte[256]);
        assertThatThrownBy(() -> ChatGptIdToken.verify(tampered, jwks, "oaiapp_x", "n1", Instant.now()))
                .isInstanceOf(ChatGptIdToken.Invalid.class);
    }

    private static String sign(KeyPair pair, String claims) throws Exception {
        String input = B64.encodeToString("{\"alg\":\"RS256\",\"kid\":\"k1\"}".getBytes(UTF_8)) + "."
                + B64.encodeToString(claims.getBytes(UTF_8));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(input.getBytes(UTF_8));
        return input + "." + B64.encodeToString(signer.sign());
    }

    private static byte[] unsigned(byte[] bytes) {
        return bytes.length > 1 && bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }
}
