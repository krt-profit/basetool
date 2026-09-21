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

import org.jetbrains.annotations.NotNull;
import org.keycloak.broker.oidc.OAuth2IdentityProviderConfig;
import org.keycloak.broker.provider.AbstractIdentityProviderFactory;
import org.keycloak.broker.social.SocialIdentityProviderFactory;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;

/**
 * Factory that registers {@link DiscordIdentityProvider} as a social identity provider.
 *
 * <p>Discovered by Keycloak via {@code META-INF/services/
 * org.keycloak.broker.social.SocialIdentityProviderFactory}, which makes "Discord" selectable in
 * the realm's <em>Identity Providers → Add provider → Social</em> menu and addressable from the
 * login flow with {@code kc_idp_hint=discord} (when the IdP alias is {@code discord}).
 */
public class DiscordIdentityProviderFactory
    extends AbstractIdentityProviderFactory<DiscordIdentityProvider>
    implements SocialIdentityProviderFactory<DiscordIdentityProvider> {

  /** Stable provider id; also the conventional IdP alias and {@code kc_idp_hint} value. */
  public static final String PROVIDER_ID = "discord";

  @Override
  public @NotNull String getName() {
    return "Discord";
  }

  @Override
  public @NotNull DiscordIdentityProvider create(
      KeycloakSession session, IdentityProviderModel model) {
    return new DiscordIdentityProvider(session, new OAuth2IdentityProviderConfig(model));
  }

  @Override
  public @NotNull OAuth2IdentityProviderConfig createConfig() {
    return new OAuth2IdentityProviderConfig();
  }

  @Override
  public @NotNull String getId() {
    return PROVIDER_ID;
  }
}
