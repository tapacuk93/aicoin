package com.aicoin.proxy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends Apple push notifications with token-based (.p8) authentication, over HTTP/2.
 *
 * <p>Configured from the environment: {@code AICOIN_PROXY_APNS_KEY} (the .p8's PEM text; literal
 * {@code \n} sequences are accepted, so it fits on one .env line) or {@code
 * AICOIN_PROXY_APNS_KEY_FILE} (a path to it), {@code AICOIN_PROXY_APNS_KEY_ID} (the key's 10-character
 * id), {@code AICOIN_PROXY_APNS_TEAM_ID} (default {@code HTS38ZPRVH}) and {@code
 * AICOIN_PROXY_APNS_TOPIC} (the app's bundle id, default {@code com.tarasmaslov.aicoinadmin}).
 * Without a key and key id, {@link #isConfigured()} is false and nothing is sent.
 */
final class Apns {

    private static final Logger LOG = Logger.getLogger(Apns.class.getName());
    private static final String PRODUCTION_HOST = "https://api.push.apple.com";
    private static final String DEVELOPMENT_HOST = "https://api.sandbox.push.apple.com";
    /** Apple rejects a provider token older than an hour and throttles refreshing more often than every 20 minutes. */
    private static final long TOKEN_LIFETIME_SECONDS = 45 * 60;

    /** What one send came to. {@code unregistered} means the device token is dead and should be forgotten. */
    record SendResult(boolean ok, boolean unregistered, String detail) {
    }

    private final PrivateKey key;
    private final String keyId;
    private final String teamId;
    private final String topic;
    private final HttpClient http;
    private String cachedJwt;
    private long cachedJwtAt;

    private Apns(PrivateKey key, String keyId, String teamId, String topic) {
        this.key = key;
        this.keyId = keyId;
        this.teamId = teamId;
        this.topic = topic;
        this.http = key == null ? null : HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    static Apns fromEnv(Map<String, String> env) {
        String teamId = env.getOrDefault("AICOIN_PROXY_APNS_TEAM_ID", "HTS38ZPRVH");
        String topic = env.getOrDefault("AICOIN_PROXY_APNS_TOPIC", "com.tarasmaslov.aicoinadmin");
        String keyId = env.getOrDefault("AICOIN_PROXY_APNS_KEY_ID", "").trim();
        String pem = env.getOrDefault("AICOIN_PROXY_APNS_KEY", "");
        String file = env.getOrDefault("AICOIN_PROXY_APNS_KEY_FILE", "");
        try {
            if (pem.isBlank() && !file.isBlank()) {
                pem = Files.readString(Path.of(file));
            }
            if (pem.isBlank() || keyId.isEmpty()) {
                LOG.info("APNs not configured (no AICOIN_PROXY_APNS_KEY[_FILE] / AICOIN_PROXY_APNS_KEY_ID): admin alerts are recorded, not pushed");
                return new Apns(null, keyId, teamId, topic);
            }
            return new Apns(parseKey(pem), keyId, teamId, topic);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "APNs key could not be loaded; admin alerts are recorded, not pushed", e);
            return new Apns(null, keyId, teamId, topic);
        }
    }

    static PrivateKey parseKey(String pem) throws Exception {
        String body = pem.replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
    }

    boolean isConfigured() {
        return key != null;
    }

    String getKeyId() {
        return keyId;
    }

    /** The ES256 provider token, reused for most of an hour as Apple asks. */
    synchronized String providerToken(long nowSeconds) throws Exception {
        if (cachedJwt != null && nowSeconds - cachedJwtAt < TOKEN_LIFETIME_SECONDS) {
            return cachedJwt;
        }
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString(("{\"alg\":\"ES256\",\"kid\":" + Json.string(keyId) + "}")
                .getBytes(StandardCharsets.UTF_8));
        String claims = b64.encodeToString(("{\"iss\":" + Json.string(teamId) + ",\"iat\":" + nowSeconds + "}")
                .getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(key);
        signer.update((header + "." + claims).getBytes(StandardCharsets.UTF_8));
        cachedJwt = header + "." + claims + "." + b64.encodeToString(signer.sign());
        cachedJwtAt = nowSeconds;
        return cachedJwt;
    }

    /** The JSON payload for one alert. */
    static String payload(String title, String body, String kind) {
        return "{\"aps\":{\"alert\":{\"title\":" + Json.string(title) + ",\"body\":" + Json.string(body)
                + "},\"sound\":\"default\",\"thread-id\":" + Json.string(kind) + "},\"kind\":" + Json.string(kind) + "}";
    }

    CompletableFuture<SendResult> send(String deviceToken, boolean development, String title, String body, String kind) {
        return send(deviceToken, development, topic, payload(title, body, kind));
    }

    /**
     * One alert push to any app. The key is a team key, so it signs for every bundle id the team
     * owns: the topic is the app's, per push, and the host follows the device's build —
     * {@code development} for a development-signed one, production for TestFlight and the store.
     */
    CompletableFuture<SendResult> send(String deviceToken, boolean development, String topic, String payload) {
        if (!isConfigured()) {
            return CompletableFuture.completedFuture(new SendResult(false, false, "APNs not configured"));
        }
        String jwt;
        try {
            jwt = providerToken(System.currentTimeMillis() / 1000);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "APNs token signing failed", e);
            return CompletableFuture.completedFuture(new SendResult(false, false, "token signing failed"));
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create((development ? DEVELOPMENT_HOST : PRODUCTION_HOST) + "/3/device/" + deviceToken))
                .timeout(Duration.ofSeconds(15))
                .header("authorization", "bearer " + jwt)
                .header("apns-topic", topic)
                .header("apns-push-type", "alert")
                .header("apns-priority", "10")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    int status = response.statusCode();
                    if (status == 200) {
                        return new SendResult(true, false, "200");
                    }
                    String reason = response.body();
                    boolean dead = status == 410 || (status == 400 && reason != null
                            && (reason.contains("BadDeviceToken") || reason.contains("DeviceTokenNotForTopic")
                                    || reason.contains("Unregistered")));
                    LOG.warning("APNs refused a push (" + status + "): " + reason);
                    return new SendResult(false, dead, status + " " + reason);
                })
                .exceptionally(err -> {
                    LOG.log(Level.WARNING, "APNs send failed", err);
                    return new SendResult(false, false, String.valueOf(err.getMessage()));
                });
    }
}
