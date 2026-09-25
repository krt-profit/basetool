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

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.protocol.oidc.mappers.OIDCIDTokenMapper;
import org.keycloak.protocol.oidc.mappers.UserInfoTokenMapper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;

/**
 * OIDC protocol mapper that emits the {@code discord_user_id} claim from the user's <em>federated
 * identity link</em> rather than from an imported user attribute.
 *
 * <p>This is the steady-state source of the Discord-link claim (epic #720 / REQ-DATA-006). The
 * older approach mapped a {@code discord_user_id} <em>user attribute</em> (written by {@link
 * DiscordUserAttributeMapper} only on the federation <em>import</em> path) into the token. That
 * attribute is written exclusively when Keycloak <em>creates</em> a user from the Discord
 * federation — i.e. for accounts that <em>registered</em> via Discord. An existing credential
 * account that links Discord later (same Keycloak subject, federated identity added afterwards)
 * goes through {@code updateBrokeredUser}, which the JSON attribute mapper only honours under sync
 * mode {@code FORCE}, and never on a pure credential login — so its attribute (and therefore the
 * claim) stayed empty and the admin member list showed no Discord icon for it.
 *
 * <p>Reading the {@link FederatedIdentityModel} instead removes that whole class of false
 * negatives: the link exists for <strong>every</strong> linked user regardless of how or when it
 * was established (Discord registration, first-broker-login account link, or account-console
 * linking), and the claim is computed at token-issuance time, so it is present on
 * <strong>every</strong> login method — including a pure username/password login that never
 * performs a Discord broker round-trip. The backend persists the claim onto {@code
 * app_user.discord_user_id} unchanged (it still reads the same claim name). The Discord user id
 * stored in the federated link is the Discord snowflake — the exact value the legacy importer
 * stored.
 *
 * <p>Registered via {@code META-INF/services/org.keycloak.protocol.ProtocolMapper}. Add it to the
 * {@code basetool-frontend} client (dedicated scope) so the claim rides the issued tokens; the
 * configured {@link #CONFIG_IDP_ALIAS identity-provider alias} must match the Discord IdP's alias
 * ({@code discord}). The raw snowflake is never logged here — only mapped into the token the
 * backend already consumes.
 */
public class DiscordFederatedIdentityMapper extends AbstractOIDCProtocolMapper
    implements OIDCAccessTokenMapper, OIDCIDTokenMapper, UserInfoTokenMapper {

  /** Stable provider id shown in the admin console and referenced from the realm config. */
  public static final String PROVIDER_ID = "discord-federated-identity-mapper";

  /** Config key — the Discord identity-provider alias whose federated link is read. */
  public static final String CONFIG_IDP_ALIAS = "idp.alias";

  /**
   * Default identity-provider alias. Matches the {@code discord} alias mandated by the deployment
   * runbook ({@code docs/keycloak/DISCORD_KEYCLOAK_SETUP.md}); the alias is the broker redirect
   * path and the {@code kc_idp_hint}, so it is fixed in practice.
   */
  public static final String DEFAULT_IDP_ALIAS = "discord";

  /** Default token claim name; the backend reads exactly this claim ({@code discord_user_id}). */
  public static final String DEFAULT_CLAIM_NAME = "discord_user_id";

  /**
   * The admin-console configuration of this mapper, frozen once built. It used to be a
   * public-facing mutable {@code ArrayList} handed straight out of {@link #getConfigProperties()},
   * so any caller inside the Keycloak JVM could add to or clear the definition every mapper
   * instance shares.
   */
  private static final @Unmodifiable List<ProviderConfigProperty> CONFIG_PROPERTIES =
      buildConfigProperties();

  /**
   * Assembles the configuration: the identity-provider alias plus Keycloak's standard claim-name
   * and include-in-tokens properties, with the claim name pre-filled.
   *
   * @return the frozen property list
   */
  private static @NotNull @Unmodifiable List<ProviderConfigProperty> buildConfigProperties() {
    ProviderConfigProperty idpAlias = new ProviderConfigProperty();
    idpAlias.setName(CONFIG_IDP_ALIAS);
    idpAlias.setLabel("Identity provider alias");
    idpAlias.setType(ProviderConfigProperty.STRING_TYPE);
    idpAlias.setDefaultValue(DEFAULT_IDP_ALIAS);
    idpAlias.setHelpText(
        "Alias of the Discord identity provider whose federated link supplies the id. Must match "
            + "the IdP alias configured in the realm (default: discord).");
    List<ProviderConfigProperty> properties = new ArrayList<>(List.of(idpAlias));

    OIDCAttributeMapperHelper.addTokenClaimNameConfig(properties);
    OIDCAttributeMapperHelper.addIncludeInTokensConfig(
        properties, DiscordFederatedIdentityMapper.class);

    for (ProviderConfigProperty property : properties) {
      if (OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME.equals(property.getName())) {
        property.setDefaultValue(DEFAULT_CLAIM_NAME);
      }
    }
    return List.copyOf(properties);
  }

  @Override
  public @NotNull String getDisplayCategory() {
    return TOKEN_MAPPER_CATEGORY;
  }

  @Override
  public @NotNull String getDisplayType() {
    return "Discord Federated Identity";
  }

  @Override
  public @NotNull String getHelpText() {
    return "Maps the user's linked Discord account id (from the federated identity link) into a "
        + "token claim. Works for accounts that registered via Discord AND accounts that linked "
        + "Discord later, on every login method — unlike importing the id into a user attribute.";
  }

  @Override
  public @NotNull @Unmodifiable List<ProviderConfigProperty> getConfigProperties() {
    return CONFIG_PROPERTIES;
  }

  @Override
  public @NotNull String getId() {
    return PROVIDER_ID;
  }

  /**
   * Resolves the user's Discord federated-identity link and, when present, writes its id into the
   * configured claim. A user with no Discord link (or a link carrying a blank id) leaves the token
   * untouched, so the claim is simply absent — exactly the "not linked" signal the backend treats
   * as {@code null}. Reads the link directly from the user store, so it is independent of how the
   * link was created and of the IdP mapper sync mode.
   *
   * @param token the token being assembled (access, id, or userinfo).
   * @param mappingModel this mapper instance's realm configuration (claim name, alias, token
   *     flags).
   * @param userSession the active user session whose {@link UserModel} owns the federated link.
   * @param keycloakSession the current Keycloak session, used for the realm and the user store.
   * @param clientSessionCtx the client session context (unused; required by the contract).
   */
  @Override
  protected void setClaim(
      @NotNull IDToken token,
      @NotNull ProtocolMapperModel mappingModel,
      @NotNull UserSessionModel userSession,
      @NotNull KeycloakSession keycloakSession,
      ClientSessionContext clientSessionCtx) {
    RealmModel realm = keycloakSession.getContext().getRealm();
    UserModel user = userSession.getUser();
    if (realm == null || user == null) {
      return;
    }

    String alias = mappingModel.getConfig().get(CONFIG_IDP_ALIAS);
    if (alias == null || alias.isBlank()) {
      alias = DEFAULT_IDP_ALIAS;
    }

    FederatedIdentityModel link = keycloakSession.users().getFederatedIdentity(realm, user, alias);
    if (link == null) {
      return;
    }
    String discordUserId = link.getUserId();
    if (discordUserId == null || discordUserId.isBlank()) {
      return;
    }

    OIDCAttributeMapperHelper.mapClaim(token, mappingModel, discordUserId);
  }
}
