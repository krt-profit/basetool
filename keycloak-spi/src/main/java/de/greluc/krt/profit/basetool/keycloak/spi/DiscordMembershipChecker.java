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
import lombok.RequiredArgsConstructor;
import lombok.extern.jbosslog.JBossLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
@JBossLog
@RequiredArgsConstructor
public class DiscordMembershipChecker {

  /** Outcome of a guild + role membership check. All non-{@code ALLOWED} values deny the login. */
  public enum Result {
    /** HTTP 200 and {@code roles[]} contains the configured KRT-Mitglied role id. */
    ALLOWED,
    /** Cleanly not a member: HTTP 404 (not in guild) or 200 without the required role. */
    DENIED_NOT_MEMBER,
    /** Fail-closed denial: 5xx / 401 / 403 / malformed body / network error / timeout / 429. */
    DENIED_ERROR
  }

  /** The HTTP client used for the Discord call. */
  private final @NotNull HttpClient httpClient;

  /** Per-request timeout; exceeding it is a fail-closed denial. */
  private final @NotNull Duration requestTimeout;

  /** How many times a {@code 429 Too Many Requests} is retried before denying. */
  private final int max429Retries;

  /** Upper bound on the wait between 429 retries (caps {@code Retry-After}). */
  private final @NotNull Duration max429Wait;

  /**
   * Decides whether the Discord user behind {@code accessToken} may log in.
   *
   * @param apiBaseUrl Discord API base URL, e.g. {@code https://discord.com/api/v10}
   * @param guildId the required guild (server) id
   * @param roleId the required role id (numeric snowflake, as a string)
   * @param accessToken the user's brokered Discord access token (scope {@code guilds.members.read})
   * @return {@link Result#ALLOWED} only for an in-guild member holding the role; a denial otherwise
   */
  public @NotNull Result check(
      @NotNull String apiBaseUrl,
      @NotNull String guildId,
      @NotNull String roleId,
      @NotNull String accessToken) {
    return lookup(apiBaseUrl, guildId, roleId, accessToken).result();
  }

  /**
   * Performs the one guild-member read of a first login and returns the membership decision
   * together with the member object it was taken from.
   *
   * <p>Exists so the first-login gate needs exactly one Discord call. It used to read the same
   * {@code /users/@me/guilds/{guildId}/member} twice — once here for the roles, once more through
   * {@link DiscordGuildNicknameReader} for the nickname — which doubled the rate-limit budget a
   * login spends and let the two answers disagree. The body is handed back only on an {@link
   * Result#ALLOWED} decision, i.e. only from a clean HTTP 200 that parsed; a caller derives the
   * nickname from it with {@link DiscordGuildNicknameReader#extractNick(String)}.
   *
   * <p>The fail-closed contract is unchanged: every outcome other than a 200 carrying the role is a
   * denial, and a denial carries no body.
   *
   * @param apiBaseUrl Discord API base URL, e.g. {@code https://discord.com/api/v10}
   * @param guildId the required guild (server) id
   * @param roleId the required role id (numeric snowflake, as a string)
   * @param accessToken the user's brokered Discord access token (scope {@code guilds.members.read})
   * @return the decision, plus the member JSON when (and only when) the login is allowed
   */
  public @NotNull MemberLookup lookup(
      @NotNull String apiBaseUrl,
      @NotNull String guildId,
      @NotNull String roleId,
      @NotNull String accessToken) {
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
        log.warnf(
            e,
            "Discord membership check failed to reach the API (%s); denying.",
            e.getClass().getSimpleName());
        return MemberLookup.denied(Result.DENIED_ERROR);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("Discord membership check was interrupted; denying.");
        return MemberLookup.denied(Result.DENIED_ERROR);
      }

      int status = response.statusCode();
      if (status == 200) {
        try {
          String body = response.body();
          return hasRole(body, roleId)
              ? new MemberLookup(Result.ALLOWED, body)
              : MemberLookup.denied(Result.DENIED_NOT_MEMBER);
        } catch (IOException e) {
          // Malformed / unparseable body — fail closed. Distinct from a transport failure: this one
          // means Discord answered 200 with something we could not read, i.e. a contract change.
          log.warnf(e, "Discord returned an unreadable member payload; denying.");
          return MemberLookup.denied(Result.DENIED_ERROR);
        }
      }
      if (status == 404) {
        // Clean "not a member of the guild".
        return MemberLookup.denied(Result.DENIED_NOT_MEMBER);
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
      log.warnf(
          "Discord membership check denied on HTTP %d after %d retry attempt(s).", status, attempt);
      return MemberLookup.denied(Result.DENIED_ERROR);
    }
  }

  /**
   * The outcome of one guild-member read: the membership decision and, on an allowed login only,
   * the raw member JSON it was decided from. Never logged — the body carries Discord ids and names.
   *
   * @param result the fail-closed membership decision
   * @param memberBody the guild-member JSON on {@link Result#ALLOWED}; {@code null} on any denial
   */
  public record MemberLookup(@NotNull Result result, @Nullable String memberBody) {

    /**
     * A denial, which by contract carries no member body.
     *
     * @param result the non-allowed decision
     * @return the lookup outcome
     */
    static @NotNull MemberLookup denied(@NotNull Result result) {
      return new MemberLookup(result, null);
    }

    /**
     * Renders the decision only, so the member body can never reach a log line through the record.
     *
     * @return the decision
     */
    @Override
    public @NotNull String toString() {
      return "MemberLookup[result=" + result + "]";
    }
  }

  private @NotNull HttpRequest buildRequest(@NotNull String url, @NotNull String accessToken) {
    return HttpRequest.newBuilder(URI.create(url))
        .timeout(requestTimeout)
        .header("Authorization", "Bearer " + accessToken)
        .header("Accept", "application/json")
        .GET()
        .build();
  }

  private boolean hasRole(@NotNull String body, @NotNull String roleId) throws IOException {
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

  private void waitForRetry(@NotNull HttpResponse<String> response) {
    long waitMs =
        response.headers().firstValue("Retry-After").map(this::parseRetryAfterMs).orElse(200L);
    waitMs = Math.min(Math.max(waitMs, 0L), max429Wait.toMillis());
    if (waitMs > 0) {
      try {
        Thread.sleep(waitMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.debug("Interrupted while backing off a Discord 429.");
      }
    }
  }

  private long parseRetryAfterMs(@NotNull String headerValue) {
    try {
      return (long) (Double.parseDouble(headerValue.trim()) * 1000);
    } catch (NumberFormatException e) {
      // A non-numeric Retry-After (HTTP-date form) — fall back to a small fixed wait.
      log.debugf("Non-numeric Discord Retry-After header; using the default backoff.");
      return 200L;
    }
  }
}
