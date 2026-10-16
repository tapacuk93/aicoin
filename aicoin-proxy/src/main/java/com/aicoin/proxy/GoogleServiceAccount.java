package com.aicoin.proxy;

import java.net.URI;
import java.net.URLEncoder;
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
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.yaml.snakeyaml.Yaml;

/**
 * An OAuth access token for a Google service account, kept fresh in the background.
 *
 * <p>Cloud Text-to-Speech refuses API keys outright ("API keys are not supported by this API"),
 * and it is the only Google surface that speaks Malagasy. Every other provider here is
 * authenticated by a fixed string; this one by a token that expires hourly and is minted by
 * signing a JWT with the account's private key and trading it at Google's token endpoint.
 *
 * <p>Minting is a blocking HTTPS call, and nothing on a Netty event loop may block, so it never
 * happens on a request's path: a daemon thread mints one at start and again every
 * {@link #REFRESH_EVERY}, well inside the hour a token lasts. A request just reads the latest. If
 * minting fails, the previous token is kept until it expires and the next attempt comes sooner;
 * a request made with no token at all fails with Google's 401 and is refunded like any other.
 */
final class GoogleServiceAccount {

    private static final Logger LOG = Logger.getLogger(GoogleServiceAccount.class.getName());
    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final Duration REFRESH_EVERY = Duration.ofMinutes(45);
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(1);

    private final String clientEmail;
    private final PrivateKey privateKey;
    private final String tokenUri;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService refresher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "google-service-account-token");
        thread.setDaemon(true);
        return thread;
    });
    private volatile String token = "";

    private GoogleServiceAccount(String clientEmail, PrivateKey privateKey, String tokenUri) {
        this.clientEmail = clientEmail;
        this.privateKey = privateKey;
        this.tokenUri = tokenUri;
    }

    /**
     * Reads a service-account key file (the JSON Google's console downloads) and starts keeping a
     * token for it. Null when the path is empty or the file can't be read — the provider is then
     * simply unconfigured, the same as any other provider with no key.
     */
    static GoogleServiceAccount start(String keyFile) {
        if (keyFile == null || keyFile.isBlank()) {
            return null;
        }
        GoogleServiceAccount account;
        try {
            account = fromJson(Files.readString(Path.of(keyFile)));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "could not read Google service account key " + keyFile, e);
            return null;
        }
        // The first token is minted here, at start-up, before anything is served. Left to the
        // background thread, the health probe that runs as the proxy starts went out with no token
        // and recorded the provider as down for its first interval.
        account.refresh();
        return account;
    }

    static GoogleServiceAccount fromJson(String json) throws Exception {
        Map<?, ?> key = (Map<?, ?>) new Yaml().load(json);
        String email = (String) key.get("client_email");
        String pem = (String) key.get("private_key");
        Object uri = key.get("token_uri");
        if (email == null || pem == null) {
            throw new IllegalArgumentException("not a service account key: no client_email/private_key");
        }
        return new GoogleServiceAccount(email, privateKey(pem),
                uri instanceof String ? (String) uri : "https://oauth2.googleapis.com/token");
    }

    /** The current access token; empty until the first one has been minted. */
    String currentToken() {
        return token;
    }

    /** Mints a token now and schedules the next; on failure, retries sooner. */
    private void refresh() {
        Duration next = REFRESH_EVERY;
        try {
            token = mint();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "could not mint a Google access token for " + clientEmail, e);
            next = RETRY_AFTER_FAILURE;
        }
        refresher.schedule(this::refresh, next.toSeconds(), TimeUnit.SECONDS);
    }

    private String mint() throws Exception {
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion(Instant.now()), StandardCharsets.UTF_8);
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(tokenUri))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("token endpoint answered " + response.statusCode() + ": "
                    + response.body().substring(0, Math.min(300, response.body().length())));
        }
        Object minted = ((Map<?, ?>) new Yaml().load(response.body())).get("access_token");
        if (!(minted instanceof String) || ((String) minted).isEmpty()) {
            throw new IllegalStateException("token endpoint returned no access_token");
        }
        return (String) minted;
    }

    /** The signed JWT traded for a token: RS256, this account, the cloud-platform scope, an hour. */
    String assertion(Instant now) throws Exception {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        long iat = now.getEpochSecond();
        String claims = b64.encodeToString(("{\"iss\":" + Json.string(clientEmail)
                + ",\"scope\":" + Json.string(SCOPE) + ",\"aud\":" + Json.string(tokenUri)
                + ",\"iat\":" + iat + ",\"exp\":" + (iat + 3600) + "}").getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update((header + "." + claims).getBytes(StandardCharsets.UTF_8));
        return header + "." + claims + "." + b64.encodeToString(signer.sign());
    }

    private static PrivateKey privateKey(String pem) throws Exception {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }
}
