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
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.core.UriBuilder;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.keycloak.broker.oidc.AbstractOAuth2IdentityProvider;
import org.keycloak.broker.oidc.OAuth2IdentityProviderConfig;
import org.keycloak.broker.oidc.mappers.AbstractJsonUserAttributeMapper;
import org.keycloak.broker.provider.AuthenticationRequest;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.broker.social.SocialIdentityProvider;
import org.keycloak.events.EventBuilder;
import org.keycloak.models.KeycloakSession;
import org.keycloak.util.JsonSerialization;

/**
 * OAuth 2.0 identity provider that brokers a Discord login into Keycloak, mapping the {@code GET
 * /users/@me} profile into a {@link BrokeredIdentityContext}.
 *
 * <p>The raw profile JSON is stored for {@link DiscordUserAttributeMapper}. The scopes include
 * {@code guilds.members.read} for {@link DiscordGuildRoleGateAuthenticator}; the provider itself
 * grants no access.
 */
public class DiscordIdentityProvider
    extends AbstractOAuth2IdentityProvider<OAuth2IdentityProviderConfig>
    implements SocialIdentityProvider<OAuth2IdentityProviderConfig> {

  /** Discord OAuth2 authorization endpoint. */
  public static final String AUTH_URL = "https://discord.com/api/oauth2/authorize";

  /** Discord OAuth2 token endpoint. */
  public static final String TOKEN_URL = "https://discord.com/api/oauth2/token";

  /** Discord API base URL shared by the profile and the guild-member (nickname) calls. */
  public static final String API_BASE_URL = "https://discord.com/api/v10";

  /** Discord current-user profile endpoint ({@code GET /users/@me}). */
  public static final String PROFILE_URL = API_BASE_URL + "/users/@me";

  /**
   * Synthetic profile field carrying the guild display name from {@link
   * DiscordGuildNicknameReader#readGuildDisplayName}, for import into the {@code
   * discord_guild_nickname} attribute (REQ-DATA-018); absent when no name was captured.
   */
  public static final String GUILD_NICK_PROFILE_FIELD = "guild_nick";

  /**
   * Environment variable holding the guild id for the nickname capture; unset or blank skips the
   * capture.
   */
  static final String GUILD_ID_ENV = "DISCORD_GUILD_ID";

  /**
   * Default OAuth2 scopes. {@code identify} + {@code email} populate the brokered profile; {@code
   * guilds.members.read} is required by the membership gate (T1.2) to read the user's roles in the
   * configured guild via the user's own token, and by the optional nickname capture below.
   */
  public static final String DEFAULT_SCOPE = "identify email guilds.members.read";

  private static final Duration HTTP_TIMEOUT = DiscordHttp.TIMEOUT;

  private static final HttpClient HTTP_CLIENT = DiscordHttp.CLIENT;

  private static final DiscordGuildNicknameReader NICKNAME_READER =
      new DiscordGuildNicknameReader(HTTP_CLIENT, HTTP_TIMEOUT);

  /**
   * Creates the provider and pins the Discord OAuth2 endpoints onto its config.
   *
   * @param session the current Keycloak session
   * @param config the brokered identity-provider config (endpoints are set here)
   */
  public DiscordIdentityProvider(
      KeycloakSession session, @NotNull OAuth2IdentityProviderConfig config) {
    super(session, config);
    config.setAuthorizationUrl(AUTH_URL);
    config.setTokenUrl(TOKEN_URL);
    config.setUserInfoUrl(PROFILE_URL);
  }

  @Override
  protected @NotNull String getDefaultScopes() {
    return DEFAULT_SCOPE;
  }

  /**
   * Appends {@code prompt=none} to the authorization request so Discord shows the consent screen
   * only on the first authorization.
   *
   * @param request the brokered authentication request being built
   * @return the authorization-URL builder with {@code prompt=none} appended
   */
  @Override
  protected @NotNull UriBuilder createAuthorizationUrl(AuthenticationRequest request) {
    return super.createAuthorizationUrl(request).queryParam("prompt", "none");
  }

  @Override
  protected @NotNull BrokeredIdentityContext doGetFederatedIdentity(@NotNull String accessToken) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(PROFILE_URL))
            .timeout(HTTP_TIMEOUT)
            .header("Authorization", "Bearer " + accessToken)
            .header("Accept", "application/json")
            .GET()
            .build();
    try {
      HttpResponse<String> response =
          HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IdentityBrokerException(
            "Discord profile request returned HTTP " + response.statusCode());
      }
      JsonNode profile = JsonSerialization.readValue(response.body(), JsonNode.class);
      enrichWithGuildNickname(profile, accessToken);
      return extractIdentityFromProfile(null, profile);
    } catch (IOException e) {
      throw new IdentityBrokerException("Could not obtain the Discord user profile", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IdentityBrokerException("Interrupted while obtaining the Discord user profile", e);
    }
  }

  /**
   * Builds the brokered identity from the Discord profile and stores the raw profile JSON for
   * {@link DiscordUserAttributeMapper}; logs no profile data or token.
   *
   * @param event the broker event builder; unused
   * @param profile the parsed {@code /users/@me} response
   * @return the brokered identity keyed by the Discord user id
   */
  @Override
  protected @NotNull BrokeredIdentityContext extractIdentityFromProfile(
      EventBuilder event, @NotNull JsonNode profile) {
    String id = getJsonProperty(profile, "id");
    String username = getJsonProperty(profile, "username");
    String email = getJsonProperty(profile, "email");

    BrokeredIdentityContext user = new BrokeredIdentityContext(id, getConfig());
    user.setUsername(username != null ? username : id);
    if (email != null) {
      user.setEmail(email);
    }
    user.setIdp(this);

    AbstractJsonUserAttributeMapper.storeUserProfileForMapper(
        user, profile, getConfig().getAlias());
    return user;
  }

  /**
   * Injects the guild display name under {@link #GUILD_NICK_PROFILE_FIELD} into the profile, using
   * {@link DiscordGuildNicknameReader#readGuildDisplayName} (REQ-DATA-018).
   *
   * <p>Skipped when {@link #GUILD_ID_ENV} is unset or the profile is not a JSON object; never
   * throws.
   *
   * @param profile the parsed profile, mutated in place when a name is found
   * @param accessToken the user's brokered Discord access token
   */
  private void enrichWithGuildNickname(@Nullable JsonNode profile, @NotNull String accessToken) {
    String guildId = configuredGuildId();
    if (guildId == null || !(profile instanceof ObjectNode objectProfile)) {
      return;
    }
    NICKNAME_READER
        .readGuildDisplayName(API_BASE_URL, guildId, accessToken)
        .ifPresent(name -> objectProfile.put(GUILD_NICK_PROFILE_FIELD, name));
  }

  /**
   * Reads the das-kartell guild id for nickname capture from the {@link #GUILD_ID_ENV} environment
   * variable.
   *
   * @return the trimmed guild id, or {@code null} when unset or blank (nickname capture disabled)
   */
  @Contract(pure = true)
  private @Nullable String configuredGuildId() {
    String value = System.getenv(GUILD_ID_ENV);
    return (value == null || value.isBlank()) ? null : value.trim();
  }
}
