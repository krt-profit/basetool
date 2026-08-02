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
import org.jboss.logging.Logger;
import org.keycloak.util.JsonSerialization;

/**
 * Pure, side-effect-free decision logic for the Discord guild + KRT-Mitglied membership gate
 * (REQ-SEC-016). Calls {@code GET {apiBaseUrl}/users/@me/guilds/{guildId}/member} with the user's
 * own brokered access token and decides whether the login may proceed.
 *
 * <p><strong>Fails closed.</strong> The login is admitted ({@link Result#ALLOWED}) <em>only</em> on
 * HTTP 200 whose {@code roles[]} contains the configured role id (matched by numeric id as a JSON
 * string). A clean HTTP 404 means "not in the guild" ({@link Result#DENIED_NOT_MEMBER}). Every
 * other outcome — 5xx, 401/403, a malformed body, a network error or timeout, or a 429 once the
 * retry budget is exhausted — is a fail-closed denial ({@link Result#DENIED_ERROR}). All three
 * non-allow results deny access; the distinction exists only for non-PII logging.
 *
 * <p>This class never logs the token, the response body, or any Discord id.
 */
public class DiscordMembershipChecker {

  /** Fail-closed denials are invisible downstream, so every reason is logged here. */
  private static final Logger LOG = Logger.getLogger(DiscordMembershipChecker.class);

  /** Outcome of a guild + role membership check. All non-{@code ALLOWED} values deny the login. */
  public enum Result {
    /** HTTP 200 and {@code roles[]} contains the configured KRT-Mitglied role id. */
    ALLOWED,
    /** Cleanly not a member: HTTP 404 (not in guild) or 200 without the required role. */
    DENIED_NOT_MEMBER,
    /** Fail-closed denial: 5xx / 401 / 403 / malformed body / network error / timeout / 429. */
    DENIED_ERROR
  }

  private final HttpClient httpClient;
  private final Duration requestTimeout;
  private final int max429Retries;
  private final Duration max429Wait;

  /**
   * Creates a checker.
   *
   * @param httpClient the HTTP client used for the Discord call
   * @param requestTimeout per-request timeout; exceeding it is a fail-closed denial
   * @param max429Retries how many times a {@code 429 Too Many Requests} is retried before denying
   * @param max429Wait upper bound on the wait between 429 retries (caps {@code Retry-After})
   */
  public DiscordMembershipChecker(
      HttpClient httpClient, Duration requestTimeout, int max429Retries, Duration max429Wait) {
    this.httpClient = httpClient;
    this.requestTimeout = requestTimeout;
    this.max429Retries = max429Retries;
    this.max429Wait = max429Wait;
  }

  /**
   * Decides whether the Discord user behind {@code accessToken} may log in.
   *
   * @param apiBaseUrl Discord API base URL, e.g. {@code https://discord.com/api/v10}
   * @param guildId the required guild (server) id
   * @param roleId the required role id (numeric snowflake, as a string)
   * @param accessToken the user's brokered Discord access token (scope {@code guilds.members.read})
   * @return {@link Result#ALLOWED} only for an in-guild member holding the role; a denial otherwise
   */
  public Result check(String apiBaseUrl, String guildId, String roleId, String accessToken) {
    String url = apiBaseUrl + "/users/@me/guilds/" + guildId + "/member";
    int attempt = 0;
    while (true) {
      HttpResponse<String> response;
      try {
        response =
            httpClient.send(buildRequest(url, accessToken), HttpResponse.BodyHandlers.ofString());
      } catch (IOException e) {
        // Timeout / connection reset / DNS failure / truncated read — fail closed. This is the
        // single most likely cause of a "nobody can log in" report, so it must not be silent: the
        // authenticator downstream only ever sees DENIED_ERROR and cannot say what went wrong.
        LOG.warnf(
            e,
            "Discord membership check failed to reach the API (%s); denying.",
            e.getClass().getSimpleName());
        return Result.DENIED_ERROR;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOG.warn("Discord membership check was interrupted; denying.");
        return Result.DENIED_ERROR;
      }

      int status = response.statusCode();
      if (status == 200) {
        try {
          return hasRole(response.body(), roleId) ? Result.ALLOWED : Result.DENIED_NOT_MEMBER;
        } catch (IOException e) {
          // Malformed / unparseable body — fail closed. Distinct from a transport failure: this one
          // means Discord answered 200 with something we could not read, i.e. a contract change.
          LOG.warnf(e, "Discord returned an unreadable member payload; denying.");
          return Result.DENIED_ERROR;
        }
      }
      if (status == 404) {
        // Clean "not a member of the guild".
        return Result.DENIED_NOT_MEMBER;
      }
      if (status == 429 && attempt < max429Retries) {
        attempt++;
        waitForRetry(response);
        continue;
      }
      // 5xx / 401 / 403 / 429-after-retries / anything unexpected — fail closed. The status is the
      // whole diagnosis: 401 means the brokered token is bad, 403 a missing scope, 429 that we are
      // being rate-limited, 5xx a Discord outage. Never log the token or the URL (it carries the
      // guild id).
      LOG.warnf(
          "Discord membership check denied on HTTP %d after %d retry attempt(s).", status, attempt);
      return Result.DENIED_ERROR;
    }
  }

  private HttpRequest buildRequest(String url, String accessToken) {
    return HttpRequest.newBuilder(URI.create(url))
        .timeout(requestTimeout)
        .header("Authorization", "Bearer " + accessToken)
        .header("Accept", "application/json")
        .GET()
        .build();
  }

  private boolean hasRole(String body, String roleId) throws IOException {
    JsonNode root = JsonSerialization.readValue(body, JsonNode.class);
    JsonNode roles = root.get("roles");
    if (roles == null || !roles.isArray()) {
      return false;
    }
    for (JsonNode role : roles) {
      if (roleId.equals(role.asText())) {
        return true;
      }
    }
    return false;
  }

  private void waitForRetry(HttpResponse<String> response) {
    long waitMs =
        response.headers().firstValue("Retry-After").map(this::parseRetryAfterMs).orElse(200L);
    waitMs = Math.min(Math.max(waitMs, 0L), max429Wait.toMillis());
    if (waitMs > 0) {
      try {
        Thread.sleep(waitMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOG.debug("Interrupted while backing off a Discord 429.");
      }
    }
  }

  private long parseRetryAfterMs(String headerValue) {
    try {
      return (long) (Double.parseDouble(headerValue.trim()) * 1000);
    } catch (NumberFormatException e) {
      // A non-numeric Retry-After (HTTP-date form) — fall back to a small fixed wait.
      LOG.debugf("Non-numeric Discord Retry-After header; using the default backoff.");
      return 200L;
    }
  }
}
