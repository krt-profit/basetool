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
import lombok.RequiredArgsConstructor;
import lombok.extern.jbosslog.JBossLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.keycloak.util.JsonSerialization;

/**
 * Best-effort reader of a Discord user's guild display name from the guild-member object, using the
 * user's own brokered token (REQ-DATA-018).
 *
 * <p>Fails open: any outcome other than an HTTP 200 with a usable name yields {@link
 * Optional#empty()}, never an exception. {@link #extractNick(String)} returns only the per-guild
 * {@code nick}; {@link #readGuildDisplayName} falls back to the global display name. Never logs the
 * token, the body or any name.
 */
@JBossLog
@RequiredArgsConstructor
public class DiscordGuildNicknameReader {

  /**
   * Defensive upper bound on the captured nickname length. Discord caps a server nickname at 32
   * characters, so this only guards against a hostile or malformed body and keeps the value well
   * within the backend column width.
   */
  private static final int MAX_NICK_LENGTH = 100;

  /** The HTTP client used for the Discord call. */
  private final @NotNull HttpClient httpClient;

  /** Per-request timeout; exceeding it yields an empty result (fail open). */
  private final @NotNull Duration requestTimeout;

  /**
   * Reads the name the guild displays for the user: the per-guild {@code nick}, else {@code
   * user.global_name}.
   *
   * @param apiBaseUrl Discord API base URL, e.g. {@code https://discord.com/api/v10}
   * @param guildId the guild whose member object is read
   * @param accessToken the user's brokered Discord access token
   * @return the trimmed, length-bounded name, or {@link Optional#empty()} when absent or on any
   *     error
   */
  public @NotNull Optional<String> readGuildDisplayName(
      @NotNull String apiBaseUrl, @NotNull String guildId, @NotNull String accessToken) {
    return fetchMemberBody(apiBaseUrl, guildId, accessToken)
        .flatMap(DiscordGuildNicknameReader::extractGuildDisplayName);
  }

  /**
   * Fetches the raw guild-member JSON body within the bounded request timeout; never throws.
   *
   * @param apiBaseUrl Discord API base URL, e.g. {@code https://discord.com/api/v10}
   * @param guildId the guild whose member object is read
   * @param accessToken the user's brokered Discord access token
   * @return the body on HTTP 200, otherwise {@link Optional#empty()}
   */
  private @NotNull Optional<String> fetchMemberBody(
      @NotNull String apiBaseUrl, @NotNull String guildId, @NotNull String accessToken) {
    String url = apiBaseUrl + "/users/@me/guilds/" + guildId + "/member";
    HttpResponse<String> response;
    try {
      response =
          httpClient.send(buildRequest(url, accessToken), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      log.debugf(
          e,
          "Could not fetch the Discord guild nickname (%s); continuing without it.",
          e.getClass().getSimpleName());
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.debug("Interrupted while fetching the Discord guild nickname; continuing without it.");
      return Optional.empty();
    }
    if (response.statusCode() != 200) {
      log.debugf(
          "Discord guild-nickname lookup answered HTTP %d; continuing without it.",
          response.statusCode());
      return Optional.empty();
    }
    return Optional.ofNullable(response.body());
  }

  private @NotNull HttpRequest buildRequest(@NotNull String url, @NotNull String accessToken) {
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
  static @NotNull Optional<String> extractNick(@Nullable String body) {
    return parseMember(body).flatMap(member -> normalizedText(member.get("nick")));
  }

  /**
   * Extracts the guild display name from a guild-member body: the non-blank {@code nick}, else
   * {@code user.global_name}, normalized like {@link #extractNick(String)}.
   *
   * @param body the raw guild-member response body
   * @return the display name, or {@link Optional#empty()} when neither field is usable or the body
   *     is unparseable
   */
  static @NotNull Optional<String> extractGuildDisplayName(@Nullable String body) {
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
  private static @NotNull Optional<JsonNode> parseMember(@Nullable String body) {
    try {
      return Optional.ofNullable(JsonSerialization.readValue(body, JsonNode.class));
    } catch (IOException e) {
      log.debugf(e, "Discord guild-nickname payload was not readable JSON; ignoring it.");
      return Optional.empty();
    }
  }

  /**
   * Trims a JSON text node and bounds it to {@value #MAX_NICK_LENGTH} characters.
   *
   * @param node the JSON node to normalize; may be {@code null}
   * @return the normalized text, or {@link Optional#empty()} when absent, null or blank
   */
  private static @NotNull Optional<String> normalizedText(@Nullable JsonNode node) {
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
