/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.frontend.e2e;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A third-party exchange client for the E2E stack: a device login with a DPoP-bound token, then
 * DPoP-signed calls to the ingest gateway (REQ-XCH-005, REQ-XCH-006).
 *
 * <p>It talks to Keycloak on {@code http://localhost:18080}, so the DPoP {@code htu} at the token
 * endpoint is that URL, and to the gateway on {@code https://localhost:11262} through the committed
 * test CA. A {@code DPoP-Nonce} challenge is answered once with a fresh proof.
 */
final class ExchangeTestClient {

  /** The OpenID Connect endpoints of the E2E realm, as this JVM reaches them. */
  private static final String OIDC =
      "http://localhost:18080/auth/realms/basetool/protocol/openid-connect";

  /** The ingest gateway, as this JVM reaches it. */
  static final String GATEWAY = "https://localhost:11262";

  /** The grant type of the device authorization grant (RFC 8628). */
  private static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";

  /** The {@code User-Agent} every gateway call carries: product, version and a URL. */
  private static final String USER_AGENT =
      "BasetoolE2eClient/1.0.0 (+https://krt-profit.github.io/basetool/)";

  /** How long {@link #awaitToken} polls the token endpoint before giving up. */
  private static final Duration TOKEN_WAIT = Duration.ofSeconds(60);

  /** The base64url encoder without padding that JOSE uses. */
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  /** The registered client id the device login and the gateway's registry use. */
  private final String clientId;

  /** The installation's P-256 key; its public half is the DPoP {@code jwk}. */
  private final KeyPair key;

  /** The plain HTTP client for Keycloak. */
  private final HttpClient keycloak = HttpClient.newHttpClient();

  /** The HTTPS client for the gateway, trusting only the committed test CA. */
  private final HttpClient gateway = BackendSeeder.trustingTestCa();

  /** The latest {@code DPoP-Nonce} per origin, sent with the next proof to that origin. */
  private final Map<String, String> nonces = new LinkedHashMap<>();

  /** The access token of the finished device login, {@code null} before it. */
  private String accessToken;

  /** The refresh token of the latest token response, {@code null} before the device login. */
  private String refreshToken;

  /**
   * Creates a client with a fresh installation key.
   *
   * @param clientId the Keycloak and registry client id
   * @throws GeneralSecurityException if the JVM offers no P-256 key generator
   */
  ExchangeTestClient(String clientId) throws GeneralSecurityException {
    this.clientId = clientId;
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
    this.key = generator.generateKeyPair();
  }

  /**
   * One started device login.
   *
   * @param deviceCode the code the client polls the token endpoint with
   * @param verificationUri the bare device page the member opens, without the user code
   * @param userCode the code the member types on the device page and compares on the consent page
   */
  record DeviceLogin(String deviceCode, String verificationUri, String userCode) {}

  /**
   * One gateway answer.
   *
   * @param status the HTTP status
   * @param body the parsed JSON body, an empty object when there was none
   */
  record Answer(int status, JsonObject body) {

    /**
     * Returns the problem code of an error answer.
     *
     * @return the {@code code} member, or {@code null} when absent
     */
    String code() {
      return body.has("code") ? body.get("code").getAsString() : null;
    }
  }

  /**
   * Starts a device login for the given scopes.
   *
   * @param scopes the space-separated scopes to request
   * @return the device code, the bare verification page and the user code
   * @throws Exception if Keycloak does not answer 200
   */
  DeviceLogin startDeviceLogin(String scopes) throws Exception {
    HttpResponse<String> answer =
        keycloak.send(
            form(OIDC + "/auth/device", Map.of("client_id", clientId, "scope", scopes), null),
            BodyHandlers.ofString());
    if (answer.statusCode() != 200) {
      throw new IllegalStateException(
          "device authorization failed: HTTP " + answer.statusCode() + " " + answer.body());
    }
    JsonObject json = JsonParser.parseString(answer.body()).getAsJsonObject();
    return new DeviceLogin(
        json.get("device_code").getAsString(),
        json.get("verification_uri").getAsString(),
        json.get("user_code").getAsString());
  }

  /**
   * Polls the token endpoint with a DPoP proof until the member has approved the login.
   *
   * @param login the started device login
   * @return the token response
   * @throws Exception if no token arrives within {@link #TOKEN_WAIT}
   */
  JsonObject awaitToken(DeviceLogin login) throws Exception {
    String url = OIDC + "/token";
    Map<String, String> fields =
        Map.of(
            "grant_type", DEVICE_GRANT, "device_code", login.deviceCode(), "client_id", clientId);
    Instant deadline = Instant.now().plus(TOKEN_WAIT);
    String last = "";
    while (Instant.now().isBefore(deadline)) {
      HttpResponse<String> answer =
          keycloak.send(form(url, fields, proof("POST", url, null)), BodyHandlers.ofString());
      remember(url, answer);
      if (answer.statusCode() == 200) {
        return keep(answer);
      }
      last = answer.statusCode() + " " + answer.body();
      if (!last.contains("use_dpop_nonce")) {
        Thread.sleep(2_000);
      }
    }
    throw new IllegalStateException("no token within " + TOKEN_WAIT + "; last answer " + last);
  }

  /**
   * Refreshes the tokens with the refresh token and a DPoP proof of the same key, answering one
   * nonce challenge.
   *
   * @return the token response
   * @throws Exception if Keycloak does not issue new tokens
   */
  JsonObject refresh() throws Exception {
    String url = OIDC + "/token";
    Map<String, String> fields =
        Map.of("grant_type", "refresh_token", "refresh_token", refreshToken, "client_id", clientId);
    String last = "";
    for (int attempt = 0; attempt < 2; attempt++) {
      HttpResponse<String> answer =
          keycloak.send(form(url, fields, proof("POST", url, null)), BodyHandlers.ofString());
      remember(url, answer);
      if (answer.statusCode() == 200) {
        return keep(answer);
      }
      last = answer.statusCode() + " " + answer.body();
      if (!last.contains("use_dpop_nonce")) {
        break;
      }
    }
    throw new IllegalStateException("the refresh failed: " + last);
  }

  /**
   * Keeps the access and refresh token of a successful token response.
   *
   * @param answer the token endpoint's 200 answer
   * @return the parsed token response
   */
  private JsonObject keep(HttpResponse<String> answer) {
    JsonObject token = JsonParser.parseString(answer.body()).getAsJsonObject();
    accessToken = token.get("access_token").getAsString();
    if (token.has("refresh_token")) {
      refreshToken = token.get("refresh_token").getAsString();
    }
    return token;
  }

  /**
   * Decodes the claims of the access token without checking its signature.
   *
   * @return the payload of the access token
   */
  JsonObject accessTokenClaims() {
    String payload = accessToken.split("\\.")[1];
    return JsonParser.parseString(
            new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8))
        .getAsJsonObject();
  }

  /**
   * Returns the RFC 7638 thumbprint of the installation key, the value {@code cnf.jkt} must carry.
   *
   * @return the base64url SHA-256 thumbprint
   * @throws GeneralSecurityException if SHA-256 is unavailable
   */
  String thumbprint() throws GeneralSecurityException {
    JsonObject jwk = jwk();
    String canonical =
        "{\"crv\":\""
            + jwk.get("crv").getAsString()
            + "\",\"kty\":\"EC\",\"x\":\""
            + jwk.get("x").getAsString()
            + "\",\"y\":\""
            + jwk.get("y").getAsString()
            + "\"}";
    return B64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * Calls the gateway with the DPoP-bound token, retrying once on a nonce challenge.
   *
   * @param method the HTTP method
   * @param path the gateway path, beginning with {@code /exchange/v1}
   * @param body the JSON body, or {@code null} for none
   * @return the answer
   * @throws Exception if the call cannot be sent
   */
  Answer call(String method, String path, JsonElement body) throws Exception {
    String url = GATEWAY + path;
    String idempotency =
        "POST".equals(method) && body != null ? UUID.randomUUID().toString() : null;
    HttpResponse<String> answer = null;
    for (int attempt = 0; attempt < 2; attempt++) {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(30))
              .header("Authorization", "DPoP " + accessToken)
              .header("DPoP", proof(method, url, accessToken))
              .header("Accept", "application/json")
              .header("User-Agent", USER_AGENT);
      if (idempotency != null) {
        request.header("Idempotency-Key", idempotency);
      }
      if (body == null) {
        request.method(method, HttpRequest.BodyPublishers.noBody());
      } else {
        request
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
      }
      answer = gateway.send(request.build(), BodyHandlers.ofString());
      remember(url, answer);
      boolean challenge =
          answer.statusCode() == 401
              && answer
                  .headers()
                  .firstValue("WWW-Authenticate")
                  .orElse("")
                  .contains("use_dpop_nonce");
      if (!challenge) {
        break;
      }
    }
    String text = answer.body();
    JsonObject json =
        text == null || text.isBlank()
            ? new JsonObject()
            : JsonParser.parseString(text).getAsJsonObject();
    return new Answer(answer.statusCode(), json);
  }

  /**
   * Resolves one reference and returns the server's ref of its single match.
   *
   * @param kind the catalogue kind, e.g. {@code BLUEPRINT}
   * @param ref the reference to resolve
   * @return the server's ref of the single match
   * @throws Exception if the call fails or the reference does not resolve
   */
  JsonObject resolve(String kind, JsonObject ref) throws Exception {
    JsonArray refs = new JsonArray();
    refs.add(ref);
    JsonArray results = resolveAll(kind, refs);
    JsonObject result = results.isEmpty() ? new JsonObject() : results.get(0).getAsJsonObject();
    if (!result.has("status") || !"resolved".equals(result.get("status").getAsString())) {
      throw new IllegalStateException(kind + " " + ref + " did not resolve: " + result);
    }
    return result.getAsJsonObject("ref");
  }

  /**
   * Resolves several references of one kind in one call.
   *
   * @param kind the catalogue kind, e.g. {@code BLUEPRINT}
   * @param refs the references, at most 500
   * @return one result per reference, in order, each with its {@code status}
   * @throws Exception if the call cannot be sent or is not answered 200
   */
  JsonArray resolveAll(String kind, JsonArray refs) throws Exception {
    JsonObject request = new JsonObject();
    request.addProperty("kind", kind);
    request.add("refs", refs);
    Answer answer = call("POST", "/exchange/v1/catalog/resolve", request);
    if (answer.status() != 200) {
      throw new IllegalStateException(kind + " resolve failed: " + answer);
    }
    return answer.body().getAsJsonArray("results");
  }

  /**
   * Builds and signs one DPoP proof.
   *
   * @param method the HTTP method
   * @param url the request URL; its query is left out of {@code htu}
   * @param token the access token for {@code ath}, or {@code null} at the token endpoint
   * @return the compact JWS
   * @throws GeneralSecurityException if signing fails
   */
  private String proof(String method, String url, String token) throws GeneralSecurityException {
    URI uri = URI.create(url);
    String htu = uri.getScheme() + "://" + uri.getAuthority() + uri.getRawPath();
    JsonObject header = new JsonObject();
    header.addProperty("typ", "dpop+jwt");
    header.addProperty("alg", "ES256");
    header.add("jwk", jwk());
    JsonObject claims = new JsonObject();
    byte[] jti = new byte[18];
    new SecureRandom().nextBytes(jti);
    claims.addProperty("jti", B64.encodeToString(jti));
    claims.addProperty("htm", method);
    claims.addProperty("htu", htu);
    claims.addProperty("iat", Instant.now().getEpochSecond());
    if (token != null) {
      claims.addProperty(
          "ath",
          B64.encodeToString(
              MessageDigest.getInstance("SHA-256")
                  .digest(token.getBytes(StandardCharsets.US_ASCII))));
    }
    String nonce = nonces.get(origin(url));
    if (nonce != null) {
      claims.addProperty("nonce", nonce);
    }
    String input =
        B64.encodeToString(header.toString().getBytes(StandardCharsets.UTF_8))
            + "."
            + B64.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
    Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
    signer.initSign(key.getPrivate());
    signer.update(input.getBytes(StandardCharsets.US_ASCII));
    return input + "." + B64.encodeToString(signer.sign());
  }

  /**
   * Returns the public JWK of the installation key.
   *
   * @return {@code kty}, {@code crv}, {@code x} and {@code y}
   */
  private JsonObject jwk() {
    ECPublicKey publicKey = (ECPublicKey) key.getPublic();
    JsonObject jwk = new JsonObject();
    jwk.addProperty("kty", "EC");
    jwk.addProperty("crv", "P-256");
    jwk.addProperty("x", B64.encodeToString(coordinate(publicKey.getW().getAffineX())));
    jwk.addProperty("y", B64.encodeToString(coordinate(publicKey.getW().getAffineY())));
    return jwk;
  }

  /**
   * Encodes a curve coordinate as exactly 32 unsigned big-endian bytes.
   *
   * @param value the coordinate
   * @return its fixed-length encoding
   */
  private static byte[] coordinate(BigInteger value) {
    byte[] raw = value.toByteArray();
    byte[] fixed = new byte[32];
    int length = Math.min(raw.length, 32);
    System.arraycopy(raw, raw.length - length, fixed, 32 - length, length);
    return fixed;
  }

  /**
   * Stores the {@code DPoP-Nonce} an answer carries for its origin.
   *
   * @param url the request URL
   * @param answer the answer
   */
  private void remember(String url, HttpResponse<String> answer) {
    answer.headers().firstValue("DPoP-Nonce").ifPresent(nonce -> nonces.put(origin(url), nonce));
  }

  /**
   * Returns the scheme and authority of a URL.
   *
   * @param url the URL
   * @return its origin
   */
  private static String origin(String url) {
    URI uri = URI.create(url);
    return uri.getScheme() + "://" + uri.getAuthority();
  }

  /**
   * Builds a form {@code POST}, with a DPoP proof when one is given.
   *
   * @param url the endpoint
   * @param fields the form fields
   * @param dpop the DPoP proof, or {@code null}
   * @return the request
   */
  private static HttpRequest form(String url, Map<String, String> fields, String dpop) {
    String body =
        fields.entrySet().stream()
            .map(e ->
                URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (dpop != null) {
      request.header("DPoP", dpop);
    }
    return request.build();
  }
}
