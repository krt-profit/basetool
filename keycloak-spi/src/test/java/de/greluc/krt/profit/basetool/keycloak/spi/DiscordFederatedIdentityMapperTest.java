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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.representations.AccessToken;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests that {@link DiscordFederatedIdentityMapper} sets the {@code discord_user_id} claim from the
 * federated link, omits it without a link or id, and defaults the alias to {@code discord}.
 */
@ExtendWith(MockitoExtension.class)
class DiscordFederatedIdentityMapperTest {

  private static final String DISCORD_ID = "123456789012345678";

  @Mock private KeycloakSession session;
  @Mock private KeycloakContext keycloakContext;
  @Mock private RealmModel realm;
  @Mock private UserProvider userProvider;
  @Mock private UserSessionModel userSession;
  @Mock private UserModel user;
  @Mock private ClientSessionContext clientSessionCtx;

  private final DiscordFederatedIdentityMapper mapper = new DiscordFederatedIdentityMapper();

  @BeforeEach
  void wireSession() {
    when(session.getContext()).thenReturn(keycloakContext);
    when(keycloakContext.getRealm()).thenReturn(realm);
    when(session.users()).thenReturn(userProvider);
    when(userSession.getUser()).thenReturn(user);
  }

  /**
   * Builds an access-token-emitting mapper model with the given alias config; a {@code null} alias
   * is omitted so the mapper exercises its default.
   *
   * @param alias the identity-provider alias to configure, or {@code null} to omit it.
   * @return the configured mapper model.
   */
  private static ProtocolMapperModel accessTokenModel(String alias) {
    Map<String, String> config = new HashMap<>();
    config.put(
        OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME,
        DiscordFederatedIdentityMapper.DEFAULT_CLAIM_NAME);
    config.put(OIDCAttributeMapperHelper.INCLUDE_IN_ACCESS_TOKEN, "true");
    if (alias != null) {
      config.put(DiscordFederatedIdentityMapper.CONFIG_IDP_ALIAS, alias);
    }
    ProtocolMapperModel model = new ProtocolMapperModel();
    model.setProtocolMapper(DiscordFederatedIdentityMapper.PROVIDER_ID);
    model.setConfig(config);
    return model;
  }

  @Test
  void setsClaim_fromFederatedLink() {
    when(userProvider.getFederatedIdentity(realm, user, "discord"))
        .thenReturn(new FederatedIdentityModel("discord", DISCORD_ID, "discorduser"));

    AccessToken token = new AccessToken();
    mapper.setClaim(token, accessTokenModel("discord"), userSession, session, clientSessionCtx);

    assertEquals(DISCORD_ID, token.getOtherClaims().get("discord_user_id"));
  }

  @Test
  void fallsBackToDefaultAlias_whenAliasConfigMissing() {
    when(userProvider.getFederatedIdentity(realm, user, "discord"))
        .thenReturn(new FederatedIdentityModel("discord", DISCORD_ID, "discorduser"));

    AccessToken token = new AccessToken();
    mapper.setClaim(token, accessTokenModel(null), userSession, session, clientSessionCtx);

    assertEquals(DISCORD_ID, token.getOtherClaims().get("discord_user_id"));
  }

  @Test
  void omitsClaim_whenNoFederatedLink() {
    when(userProvider.getFederatedIdentity(eq(realm), eq(user), eq("discord"))).thenReturn(null);

    AccessToken token = new AccessToken();
    mapper.setClaim(token, accessTokenModel("discord"), userSession, session, clientSessionCtx);

    assertFalse(token.getOtherClaims().containsKey("discord_user_id"));
  }

  @Test
  void omitsClaim_whenLinkIdBlank() {
    when(userProvider.getFederatedIdentity(realm, user, "discord"))
        .thenReturn(new FederatedIdentityModel("discord", "   ", "discorduser"));

    AccessToken token = new AccessToken();
    mapper.setClaim(token, accessTokenModel("discord"), userSession, session, clientSessionCtx);

    assertFalse(token.getOtherClaims().containsKey("discord_user_id"));
  }
}
