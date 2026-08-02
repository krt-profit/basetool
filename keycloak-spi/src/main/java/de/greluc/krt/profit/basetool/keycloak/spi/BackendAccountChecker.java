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

package de.greluc.krt.profit.basetool.keycloak.spi;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jboss.logging.Logger;
import org.keycloak.util.JsonSerialization;

/**
 * Pure HTTP client for the Basetool "does an account already exist for this Discord identity?"
 * precheck (REQ-SEC-022). Calls {@code POST {url}} (the internal {@code
 * /internal/discord/account-existence} endpoint) over HTTPS, presenting the shared secret in the
 * {@code X-KRT-SPI-Secret} header and the candidate names/e-mail as JSON, and maps the {@code
 * {"exists": <bool>}} body to a {@link Result}.
 *
 * <p><strong>Fails open.</strong> Unlike {@link DiscordMembershipChecker} (the fail-closed security
 * gate), this is a duplicate-account guard, not a security boundary: only a confident {@link
 * Result#EXISTS} (HTTP 200 with {@code exists=true}) denies the login. Every ambiguity — a non-200
 * status (incl. the {@code 503} the backend returns when the feature is unconfigured, or a {@code
 * 401} on a bad secret), a TLS handshake failure against the backend's certificate, a timeout, a
 * network error, or a malformed/absent {@code exists} field — yields {@link Result#UNKNOWN}, which
 * the caller treats as "allow" so a transient hiccup never blocks a legitimate new member.
 *
 * <p>This class never logs the secret, the candidate names/e-mail, or the response body.
 */
public class BackendAccountChecker {

  /** A fail-open UNKNOWN silently disables the duplicate-account check, so each cause is logged. */
  private static final Logger LOG = Logger.getLogger(BackendAccountChecker.class);

  /** HTTP header carrying the shared secret presented to the internal backend endpoint. */
  static final String SECRET_HEADER = "X-KRT-SPI-Secret";

  /** Outcome of the account-existence precheck. Only {@link #EXISTS} denies the login. */
  public enum Result {
    /** HTTP 200 with {@code exists=true}: an existing account collides — deny + link hint. */
    EXISTS,
    /** HTTP 200 with {@code exists=false}: no collision — allow the registration. */
    NOT_EXISTS,
    /** Any ambiguity (non-200, TLS/timeout/network error, malformed body): fail open → allow. */
    UNKNOWN
  }

  private final HttpClient httpClient;
  private final Duration requestTimeout;

  /**
   * Creates a checker.
   *
   * @param httpClient the HTTP client used for the backend call; its {@code SSLContext} must trust
   *     the backend's certificate (a TLS failure simply yields {@link Result#UNKNOWN})
   * @param requestTimeout per-request timeout; exceeding it yields {@link Result#UNKNOWN}
   */
  public BackendAccountChecker(HttpClient httpClient, Duration requestTimeout) {
    this.httpClient = httpClient;
    this.requestTimeout = requestTimeout;
  }

  /**
   * Asks the backend whether an account already exists for the supplied Discord identity.
   *
   * @param url the internal account-existence endpoint URL (HTTPS; enforced by the caller)
   * @param sharedSecret the shared secret presented in the {@code X-KRT-SPI-Secret} header
   * @param username the brokered Discord username; may be {@code null}/blank (then omitted)
   * @param email the brokered Discord e-mail; may be {@code null}/blank (then omitted)
   * @param serverNickname the per-guild server nickname; may be {@code null}/blank (then omitted)
   * @return {@link Result#EXISTS} only on a clean positive; otherwise {@link Result#NOT_EXISTS} or,
   *     on any error/ambiguity, {@link Result#UNKNOWN} (fail open)
   */
  public Result check(
      String url, String sharedSecret, String username, String email, String serverNickname) {
    String body;
    try {
      body = buildBody(username, email, serverNickname);
    } catch (IOException e) {
      LOG.warnf(e, "Could not serialise the account-existence probe body; skipping the check.");
      return Result.UNKNOWN;
    }

    HttpResponse<String> response;
    try {
      response =
          httpClient.send(
              buildRequest(url, sharedSecret, body), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      // TLS handshake failure / timeout / connection reset / DNS failure — fail open. Logged
      // because failing OPEN means the duplicate-account check silently did not happen: the login
      // proceeds and nothing else records that the probe never ran.
      LOG.warnf(
          e,
          "Account-existence probe could not reach the backend (%s); skipping the check.",
          e.getClass().getSimpleName());
      return Result.UNKNOWN;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn("Account-existence probe was interrupted; skipping the check.");
      return Result.UNKNOWN;
    }

    if (response.statusCode() != 200) {
      // 503 (feature off) / 401 (bad secret) / 5xx / anything else — fail open. The status is the
      // diagnosis and a 401 in particular is a misconfiguration that would otherwise never surface.
      LOG.warnf(
          "Account-existence probe answered HTTP %d; skipping the check.", response.statusCode());
      return Result.UNKNOWN;
    }
    return parseExists(response.body());
  }

  /**
   * Serialises the non-blank candidate fields into the request JSON, omitting blanks so the backend
   * never matches on an empty value.
   *
   * @param username the candidate username
   * @param email the candidate e-mail
   * @param serverNickname the candidate server nickname
   * @return the JSON request body
   * @throws IOException when serialisation fails
   */
  private static String buildBody(String username, String email, String serverNickname)
      throws IOException {
    Map<String, String> payload = new LinkedHashMap<>();
    putIfPresent(payload, "username", username);
    putIfPresent(payload, "email", email);
    putIfPresent(payload, "serverNickname", serverNickname);
    return JsonSerialization.writeValueAsString(payload);
  }

  /**
   * Puts {@code value} under {@code key} only when it is non-{@code null} and non-blank.
   *
   * @param payload the request payload being assembled
   * @param key the JSON field name
   * @param value the candidate value; may be {@code null}/blank
   */
  private static void putIfPresent(Map<String, String> payload, String key, String value) {
    if (value != null && !value.isBlank()) {
      payload.put(key, value);
    }
  }

  /**
   * Builds the POST request with the shared-secret header and a bounded timeout.
   *
   * @param url the endpoint URL
   * @param sharedSecret the shared secret for the {@code X-KRT-SPI-Secret} header
   * @param body the JSON request body
   * @return the prepared request
   */
  private HttpRequest buildRequest(String url, String sharedSecret, String body) {
    return HttpRequest.newBuilder(URI.create(url))
        .timeout(requestTimeout)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .header(SECRET_HEADER, sharedSecret)
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
  }

  /**
   * Parses the {@code {"exists": <bool>}} response body. A missing or non-boolean {@code exists}
   * field, or an unparseable body, is treated as {@link Result#UNKNOWN} (fail open).
   *
   * @param body the raw response body
   * @return {@link Result#EXISTS} / {@link Result#NOT_EXISTS} on a clean boolean, else {@link
   *     Result#UNKNOWN}
   */
  private static Result parseExists(String body) {
    JsonNode root;
    try {
      root = JsonSerialization.readValue(body, JsonNode.class);
    } catch (IOException e) {
      LOG.warnf(e, "Account-existence response was not readable JSON; skipping the check.");
      return Result.UNKNOWN;
    }
    if (root == null) {
      return Result.UNKNOWN;
    }
    JsonNode exists = root.get("exists");
    if (exists == null || !exists.isBoolean()) {
      return Result.UNKNOWN;
    }
    return exists.asBoolean() ? Result.EXISTS : Result.NOT_EXISTS;
  }
}
