package jobapply.config;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 校验「用 ChatGPT 登录」返回的 id_token（C028）：签名对上 JWKS（RS256 / ES256，用 JDK 自带的算法，不加依赖），
 * iss、aud、exp、nonce 都对。通过后给出 email 和 sub。异常信息里不带令牌。
 */
final class ChatGptIdToken {

    static final String ISSUER = "https://auth.openai.com";
    private static final long SKEW_SECONDS = 60;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    record Claims(String subject, String email) {
    }

    /** 校验不通过。 */
    static final class Invalid extends Exception {

        Invalid(String message) {
            super(message);
        }
    }

    /** JWKS 里没有 id_token 头里的 kid：调用方可以重新取一次 JWKS 再试。 */
    static final class UnknownKey extends Exception {

        UnknownKey() {
            super("JWKS 里找不到签名用的密钥");
        }
    }

    private ChatGptIdToken() {
    }

    static Claims verify(String idToken, JsonNode jwks, String clientId, String nonce, Instant now)
            throws Invalid, UnknownKey {
        if (idToken == null) {
            throw new Invalid("换令牌的返回里没有 id_token");
        }
        String[] parts = idToken.split("\\.");
        if (parts.length != 3) {
            throw new Invalid("id_token 格式不对");
        }
        JsonNode header = json(parts[0]);
        JsonNode claims = json(parts[1]);
        String alg = header.path("alg").asString("");
        PublicKey key = key(jwks, header.path("kid").asString(null), alg);
        byte[] signed = (parts[0] + "." + parts[1]).getBytes(US_ASCII);
        byte[] signature = decode(parts[2]);
        try {
            Signature verifier = switch (alg) {
                case "RS256" -> Signature.getInstance("SHA256withRSA");
                case "ES256" -> Signature.getInstance("SHA256withECDSAinP1363Format");
                default -> throw new Invalid("id_token 的签名算法不支持：" + alg);
            };
            verifier.initVerify(key);
            verifier.update(signed);
            if (!verifier.verify(signature)) {
                throw new Invalid("id_token 签名不对");
            }
        } catch (GeneralSecurityException e) {
            throw new Invalid("id_token 签名校验失败：" + e.getClass().getSimpleName());
        }
        if (!ISSUER.equals(claims.path("iss").asString(""))) {
            throw new Invalid("id_token 的签发方不对");
        }
        JsonNode aud = claims.path("aud");
        boolean audOk = aud.isString() && clientId.equals(aud.asString());
        if (aud.isArray()) {
            for (JsonNode one : aud) {
                audOk |= one.isString() && clientId.equals(one.asString());
            }
        }
        if (!audOk) {
            throw new Invalid("id_token 的 aud 和 client_id 不一致");
        }
        if (!claims.path("exp").isNumber() || claims.path("exp").asLong() + SKEW_SECONDS < now.getEpochSecond()) {
            throw new Invalid("id_token 已过期");
        }
        if (nonce == null || !nonce.equals(claims.path("nonce").asString(""))) {
            throw new Invalid("id_token 的 nonce 不对");
        }
        String subject = claims.path("sub").asString(null);
        if (subject == null || subject.isBlank()) {
            throw new Invalid("id_token 里没有 sub");
        }
        String email = claims.path("email").asString(null);
        return new Claims(subject, email == null || email.isBlank() ? null : email);
    }

    private static PublicKey key(JsonNode jwks, String kid, String alg) throws Invalid, UnknownKey {
        String kty = switch (alg) {
            case "RS256" -> "RSA";
            case "ES256" -> "EC";
            default -> throw new Invalid("id_token 的签名算法不支持：" + alg);
        };
        JsonNode found = null;
        for (JsonNode candidate : jwks.path("keys")) {
            if (!kty.equals(candidate.path("kty").asString(""))) {
                continue;
            }
            if (kid == null || kid.equals(candidate.path("kid").asString(null))) {
                found = candidate;
                break;
            }
        }
        if (found == null) {
            throw new UnknownKey();
        }
        try {
            if (kty.equals("RSA")) {
                return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                        new BigInteger(1, decode(found.path("n").asString(""))),
                        new BigInteger(1, decode(found.path("e").asString("")))));
            }
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            ECPoint point = new ECPoint(new BigInteger(1, decode(found.path("x").asString(""))),
                    new BigInteger(1, decode(found.path("y").asString(""))));
            return KeyFactory.getInstance("EC").generatePublic(
                    new ECPublicKeySpec(point, params.getParameterSpec(ECParameterSpec.class)));
        } catch (GeneralSecurityException e) {
            throw new Invalid("JWKS 里的密钥读不出来：" + e.getClass().getSimpleName());
        }
    }

    private static JsonNode json(String part) throws Invalid {
        try {
            return JSON.readTree(new String(decode(part), UTF_8));
        } catch (JacksonException e) {
            throw new Invalid("id_token 格式不对");
        }
    }

    private static byte[] decode(String part) throws Invalid {
        try {
            return Base64.getUrlDecoder().decode(part);
        } catch (IllegalArgumentException e) {
            throw new Invalid("id_token 格式不对");
        }
    }
}
