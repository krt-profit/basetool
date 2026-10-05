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

package de.greluc.krt.profit.basetool.backend.service.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.config.KeycloakSyncProperties;
import de.greluc.krt.profit.basetool.backend.exchange.api.IngestGatewayProperties;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ChangeSourceProperties;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ConnectedAppsProperties;
import de.greluc.krt.profit.basetool.backend.platform.api.PartialRoleScopeProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests which client ids count as the Basetool's own (REQ-XCH-003). */
class FirstPartyClientIdsTest {

  private final FirstPartyClientIds ids =
      new FirstPartyClientIds(
          new IngestGatewayProperties(List.of("gw-client")),
          new ConnectedAppsProperties(List.of("web-a")),
          new ChangeSourceProperties(List.of("web-b"), List.of("app-a")),
          new PartialRoleScopeProperties(List.of("app-b", " ")),
          new KeycloakSyncProperties(
              false,
              "0 0 5 * * *",
              "Europe/Berlin",
              "https://keycloak.example",
              "iri",
              "admin-client",
              "throwaway",
              100));

  @ParameterizedTest
  @ValueSource(strings = {"gw-client", "web-a", "web-b", "app-a", "app-b", "admin-client"})
  void everyConfiguredFirstPartyIdIsRecognised(String clientId) {
    assertThat(ids.contains(clientId)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"versekit", "basetool-sc-extractor", " ", ""})
  void anExternalOrBlankIdIsNot(String clientId) {
    assertThat(ids.contains(clientId)).isFalse();
  }

  @Test
  void nullIsNot() {
    assertThat(ids.contains(null)).isFalse();
  }
}
