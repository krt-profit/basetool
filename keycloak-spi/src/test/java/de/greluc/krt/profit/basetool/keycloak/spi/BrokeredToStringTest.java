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

import de.greluc.krt.profit.basetool.keycloak.spi.DiscordGuildRoleGateAuthenticator.Brokered;
import org.junit.jupiter.api.Test;

/** Tests that {@link Brokered#toString()} reports presence only and never a value. */
class BrokeredToStringTest {

  @Test
  void toStringNeverCarriesTokenUsernameOrEmail() {
    String text = new Brokered("tok-secret-123", "alice-discord", "alice@example.test").toString();

    assertFalse(text.contains("tok-secret-123"));
    assertFalse(text.contains("alice-discord"));
    assertFalse(text.contains("alice@example.test"));
    assertEquals("Brokered[accessToken=true, username=true, email=true]", text);
  }

  @Test
  void toStringShowsAbsentFieldsAsFalse() {
    assertEquals(
        "Brokered[accessToken=false, username=false, email=false]",
        new Brokered(null, null, null).toString());
  }
}
