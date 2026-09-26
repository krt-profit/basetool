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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.provider.ProviderConfigProperty;

/** Unit tests for {@link DiscordGuildRoleGateAuthenticatorFactory}. */
class DiscordGuildRoleGateAuthenticatorFactoryTest {

  private final DiscordGuildRoleGateAuthenticatorFactory factory =
      new DiscordGuildRoleGateAuthenticatorFactory();

  @Test
  void declaresStableIdAndIsConfigurable() {
    assertEquals("discord-guild-role-gate", factory.getId());
    assertTrue(factory.isConfigurable());
    assertFalse(factory.isUserSetupAllowed());
  }

  @Test
  void offersRequiredAndDisabledOnly() {
    List<Requirement> choices = List.of(factory.getRequirementChoices());

    assertEquals(List.of(Requirement.REQUIRED, Requirement.DISABLED), choices);
  }

  @Test
  void exposesGuildRoleAndApiBaseConfigKeys() {
    Set<String> keys =
        factory.getConfigProperties().stream()
            .map(ProviderConfigProperty::getName)
            .collect(Collectors.toSet());

    assertTrue(keys.contains(DiscordGuildRoleGateAuthenticatorFactory.CONFIG_GUILD_ID));
    assertTrue(keys.contains(DiscordGuildRoleGateAuthenticatorFactory.CONFIG_KRT_MITGLIED_ROLE_ID));
    assertTrue(keys.contains(DiscordGuildRoleGateAuthenticatorFactory.CONFIG_API_BASE_URL));
  }
}
