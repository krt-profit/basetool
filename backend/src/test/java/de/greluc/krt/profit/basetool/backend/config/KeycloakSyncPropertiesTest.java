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

package de.greluc.krt.profit.basetool.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link KeycloakSyncProperties} defaults and that its {@code toString()} never prints the
 * client secret.
 */
class KeycloakSyncPropertiesTest {

  private static KeycloakSyncProperties configured() {
    return BoundProperties.bind(
        KeycloakSyncProperties.class,
        Map.of(
            "admin-url", "http://keycloak.test:8080",
            "realm", "iri",
            "client-id", "sync-client",
            "client-secret", "not-a-real-secret"));
  }

  @Test
  void unsetKeysKeepTheirDefaults() {
    KeycloakSyncProperties properties = configured();

    assertThat(properties.enabled()).isTrue();
    assertThat(properties.cron()).isEqualTo("0 0 5 * * *");
    assertThat(properties.zone()).isEqualTo("Europe/Berlin");
    assertThat(properties.pageSize()).isEqualTo(100);
  }

  @Test
  void toStringNamesTheClientButNeverPrintsTheSecret() {
    String printed = configured().toString();

    assertThat(printed)
        .contains("sync-client", "iri", "<redacted>")
        .doesNotContain("not-a-real-secret");
  }
}
