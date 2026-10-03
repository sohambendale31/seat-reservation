/// Offline HS256 token minter. Neither the burst script nor an evaluator needs it: both use
/// POST /auth/token. Run with: java scripts/MintToken.java --sub alice --roles USER[,ADMIN]
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class MintToken {

    private static final String ISSUER = "seat-reservation";
    private static final String AUDIENCE = "seat-reservation-api";
    private static final String DEV_SECRET = "local-dev-only-hs256-secret-change-me-0123456789";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    public static void main(String[] args) throws Exception {
        String sub = null;
        String roles = "USER";
        long ttl = 3600;
        String secret = System.getenv().getOrDefault("JWT_SECRET", DEV_SECRET);

        for (int i = 0; i < args.length; i++) {
            String value = i + 1 < args.length ? args[i + 1] : null;
            switch (args[i]) {
                case "--sub" -> { sub = require("--sub", value); i++; }
                case "--roles" -> { roles = require("--roles", value); i++; }
                case "--ttl" -> { ttl = Long.parseLong(require("--ttl", value)); i++; }
                case "--secret" -> { secret = require("--secret", value); i++; }
                case "--help", "-h" -> { usage(); return; }
                default -> throw new IllegalArgumentException("unknown option: " + args[i]);
            }
        }
        if (sub == null) {
            usage();
            System.exit(2);
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("secret must be at least 32 bytes");
        }

        long issuedAt = Instant.now().getEpochSecond();
        String rolesJson = Stream.of(roles.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .map(role -> "\"" + role + "\"")
                .collect(Collectors.joining(",", "[", "]"));

        String header = """
                {"alg":"HS256","typ":"JWT"}""";
        String payload = """
                {"iss":"%s","aud":"%s","sub":"%s","roles":%s,"iat":%d,"exp":%d}"""
                .formatted(ISSUER, AUDIENCE, sub, rolesJson, issuedAt, issuedAt + ttl);

        String signingInput = encode(header) + "." + encode(payload);
        System.out.println(signingInput + "." + B64.encodeToString(sign(signingInput, secret)));
    }

    private static byte[] sign(String signingInput, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String json) {
        return B64.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String require(String option, String value) {
        if (value == null) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return value;
    }

    private static void usage() {
        System.err.println("""
                usage: java scripts/MintToken.java --sub <subject> [--roles USER,ADMIN]
                                                   [--ttl <seconds>] [--secret <secret>]
                --secret defaults to $JWT_SECRET, then to the local dev secret.""");
    }
}
