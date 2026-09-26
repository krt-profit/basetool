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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.keycloak.broker.oidc.OAuth2IdentityProviderConfig;
import org.keycloak.models.IdentityProviderModel;

/** Unit tests for {@link DiscordIdentityProviderFactory}. */
class DiscordIdentityProviderFactoryTest {

  private final DiscordIdentityProviderFactory factory = new DiscordIdentityProviderFactory();

  @Test
  void exposesStableDiscordIdentity() {
    assertEquals("discord", factory.getId(), "kc_idp_hint alias must stay 'discord'");
    assertEquals("Discord", factory.getName());
    assertNotNull(factory.createConfig());
  }

  @Test
  void createsProviderWithDiscordEndpoints() {
    IdentityProviderModel model = new IdentityProviderModel();
    model.setProviderId(DiscordIdentityProviderFactory.PROVIDER_ID);
    model.setAlias("discord");

    DiscordIdentityProvider provider = factory.create(null, model);

    OAuth2IdentityProviderConfig config = provider.getConfig();
    assertEquals(DiscordIdentityProvider.AUTH_URL, config.getAuthorizationUrl());
    assertEquals(DiscordIdentityProvider.TOKEN_URL, config.getTokenUrl());
    assertEquals(DiscordIdentityProvider.PROFILE_URL, config.getUserInfoUrl());
  }
}
