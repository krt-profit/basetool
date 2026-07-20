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
import java.util.Optional;
import org.keycloak.util.JsonSerialization;

/**
 * Best-effort reader for a Discord user's per-guild server nickname — the {@code nick} field of the
 * guild-member object returned by {@code GET {apiBaseUrl}/users/@me/guilds/{guildId}/member}.
 * Called with the user's own brokered access token (scope {@code guilds.members.read}) so no bot is
 * needed, the same source the membership gate uses (REQ-DATA-008).
 *
 * <p><strong>Fails open.</strong> Unlike {@link DiscordMembershipChecker} (which gates the login
 * and fails <em>closed</em>), the nickname is purely cosmetic — it is shown to an admin at approval
 * time only. Any outcome other than an HTTP 200 carrying a usable name (a non-200 status, an
 * absent/null/blank value, a malformed body, a network error, or a timeout) yields {@link
 * Optional#empty()} and never throws. Capturing the nickname therefore cannot break the login and
 * cannot delay it beyond the bounded request timeout; the caller treats an empty result as "no
 * nickname captured".
 *
 * <p>Two views of the same guild-member object are exposed. {@link #readNickname} returns only the
 * explicit per-guild {@code nick} — used by the first-broker-login collision precheck, kept
 * conservative so a common global display name never triggers a false match (and never denies a
 * login the admin would rather resolve manually). {@link #readGuildDisplayName} returns the name
 * the guild actually shows for the member — the per-guild {@code nick} if set, otherwise the
 * account's global display name ({@code user.global_name}) — used for the admin approval-queue
 * label, so a member who never set a server nick is still shown a recognisable name instead of a
 * blank em-dash (REQ-DATA-008; the reported conrad7247/MadrukSedras case, where the server name is
 * the global display name and no per-guild nick is set).
 *
 * <p>This class never logs the token, the response body, or any captured name.
 */
public class DiscordGuildNicknameReader {

  /**
   * Defensive upper bound on the captured nickname length. Discord caps a server nickname at 32
   * characters, so this only guards against a hostile or malformed body and keeps the value well
   * within the backend column width.
   */
  private static final int MAX_NICK_LENGTH = 100;

  private final HttpClient httpClient;
  private final Duration requestTimeout;

  /**
   * Creates a reader.
   *
   * @param httpClient the HTTP client used for the Discord call
   * @param requestTimeout per-request timeout; exceeding it yields an empty result (fail open)
   */
  public DiscordGuildNicknameReader(HttpClient httpClient, Duration requestTimeout) {
    this.httpClient = httpClient;
    this.requestTimeout = requestTimeout;
  }

  /**
   * Reads the user's explicit per-guild server nickname ({@code nick}) in the given guild,
   * best-effort. Conservative view for the collision precheck: it never falls back to the global
   * display name, so a common {@code global_name} cannot trigger a false account match.
   *
   * @param apiBaseUrl Discord API base URL, e.g. {@code https://discord.com/api/v10}
   * @param guildId the guild (server) id whose nickname is wanted
   * @param accessToken the user's brokered Discord access token (scope {@code guilds.members.read})
   * @return the trimmed, length-bounded per-guild nickname, or {@link Optional#empty()} when absent
   *     or on any error
   */
  public Optional<String> readNickname(String apiBaseUrl, String guildId, String accessToken) {
    return fetchMemberBody(apiBaseUrl, guildId, accessToken)
        .flatMap(DiscordGuildNicknameReader::extractNick);
  }

  /**
   * Reads the name the guild <em>displays</em> for the user, best-effort: the per-guild {@code
   * nick} if set, otherwise the account's global display name ({@code user.global_name}). This is
   * the recognisable label shown in the admin approval queue (REQ-DATA-008), so a member who never
   * set a server nickname is still identifiable rather than a blank em-dash.
   *
   * @param apiBaseUrl Discord API base URL, e.g. {@code https://discord.com/api/v10}
   * @param guildId the guild (server) id whose member object is read
   * @param accessToken the user's brokered Discord access token (scope {@code guilds.members.read})
   * @return the trimmed, length-bounded guild display name, or {@link Optional#empty()} when
   *     neither a nickname nor a global name is present, or on any error
   */
  public Optional<String> readGuildDisplayName(
      String apiBaseUrl, String guildId, String accessToken) {
    return fetchMemberBody(apiBaseUrl, guildId, accessToken)
        .flatMap(DiscordGuildNicknameReader::extractGuildDisplayName);
  }

  /**
   * Fetches the raw guild-member JSON body, best-effort, shared by both read views. Any outcome
   * other than an HTTP 200 (a non-200 status, network error, or timeout) yields {@link
   * Optional#empty()} and never throws, so capturing a name can never break or delay the login
   * beyond the bounded request timeout.
   *
   * @param apiBaseUrl Discord API base URL, e.g. {@code https://discord.com/api/v10}
   * @param guildId the guild (server) id whose member object is read
   * @param accessToken the user's brokered Discord access token (scope {@code guilds.members.read})
   * @return the raw response body on HTTP 200, otherwise {@link Optional#empty()}
   */
  private Optional<String> fetchMemberBody(String apiBaseUrl, String guildId, String accessToken) {
    String url = apiBaseUrl + "/users/@me/guilds/" + guildId + "/member";
    HttpResponse<String> response;
    try {
      response =
          httpClient.send(buildRequest(url, accessToken), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      // Timeout / connection reset / DNS failure / truncated read — fail open (no name).
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
    if (response.statusCode() != 200) {
      return Optional.empty();
    }
    return Optional.ofNullable(response.body());
  }

  private HttpRequest buildRequest(String url, String accessToken) {
    return HttpRequest.newBuilder(URI.create(url))
        .timeout(requestTimeout)
        .header("Authorization", "Bearer " + accessToken)
        .header("Accept", "application/json")
        .GET()
        .build();
  }

  /**
   * Extracts and normalises the {@code nick} field from a guild-member JSON body.
   *
   * @param body the raw guild-member response body
   * @return the trimmed nickname (at most {@value #MAX_NICK_LENGTH} characters), or {@link
   *     Optional#empty()} when the field is absent, null, blank, or the body is unparseable
   */
  static Optional<String> extractNick(String body) {
    return parseMember(body).flatMap(member -> normalizedText(member.get("nick")));
  }

  /**
   * Extracts the name the guild displays for the member from a guild-member JSON body: the
   * per-guild {@code nick} if present and non-blank, otherwise the account's global display name
   * ({@code user.global_name}). Trimmed and length-bounded exactly like {@link
   * #extractNick(String)} — the fallback is what lets a member with no per-guild nickname still
   * surface a recognisable name in the approval queue (REQ-DATA-008).
   *
   * @param body the raw guild-member response body
   * @return the guild display name (nick, else global name), or {@link Optional#empty()} when
   *     neither field is usable or the body is unparseable
   */
  static Optional<String> extractGuildDisplayName(String body) {
    return parseMember(body)
        .flatMap(
            member -> {
              Optional<String> nick = normalizedText(member.get("nick"));
              if (nick.isPresent()) {
                return nick;
              }
              JsonNode user = member.get("user");
              return user == null ? Optional.empty() : normalizedText(user.get("global_name"));
            });
  }

  /**
   * Parses a guild-member JSON body into a node, mapping a {@code null} or unparseable body to
   * {@link Optional#empty()}.
   *
   * @param body the raw guild-member response body
   * @return the parsed member node, or {@link Optional#empty()} when the body is null/unparseable
   */
  private static Optional<JsonNode> parseMember(String body) {
    try {
      return Optional.ofNullable(JsonSerialization.readValue(body, JsonNode.class));
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /**
   * Trims a JSON text node's value, maps absent/null/blank to {@link Optional#empty()}, and bounds
   * the result to {@value #MAX_NICK_LENGTH} characters (defence against a hostile or malformed
   * body).
   *
   * @param node the JSON node to normalise; may be {@code null}
   * @return the trimmed, length-bounded text, or {@link Optional#empty()} when absent/null/blank
   */
  private static Optional<String> normalizedText(JsonNode node) {
    if (node == null || node.isNull()) {
      return Optional.empty();
    }
    String value = node.asText().trim();
    if (value.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        value.length() > MAX_NICK_LENGTH ? value.substring(0, MAX_NICK_LENGTH) : value);
  }
}
