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

package de.greluc.krt.profit.basetool.backend.identity.api;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.kernel.SquadronReferenceDto;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link UserDtoRedaction#toPeerShape}: what a non-admin sees of another member. */
class UserDtoRedactionTest {

  private static final SquadronReferenceDto SQUADRON =
      new SquadronReferenceDto(UUID.randomUUID(), "Kartell", "KRT");

  private static UserDto full(String username, String displayName) {
    return new UserDto(
        UUID.randomUUID(),
        username,
        displayName,
        displayName == null ? username : displayName,
        "mail@example.invalid",
        5,
        "about me",
        Set.of("ROLE_X"),
        Set.of("P"),
        UUID.randomUUID(),
        true,
        true,
        true,
        SQUADRON,
        List.of(SQUADRON),
        3L,
        LocalDate.of(2024, 1, 2),
        true);
  }

  @Test
  void adminOnlyFieldsAreRemoved() {
    UserDto peer = UserDtoRedaction.toPeerShape(full("bob", "Bob"));

    assertThat(peer.email()).isNull();
    assertThat(peer.roles()).isNull();
    assertThat(peer.permissions()).isNull();
    assertThat(peer.lastReadAnnouncementId()).isNull();
    assertThat(peer.isLogistician()).isFalse();
    assertThat(peer.isMissionManager()).isFalse();
    assertThat(peer.joinDate()).isNull();
    assertThat(peer.discordLinked()).isNull();
  }

  @Test
  void descriptionRankDisplayNameAndSquadronsStayVisible() {
    UserDto peer = UserDtoRedaction.toPeerShape(full("bob", "Bob"));

    assertThat(peer.description()).isEqualTo("about me");
    assertThat(peer.rank()).isEqualTo(5);
    assertThat(peer.displayName()).isEqualTo("Bob");
    assertThat(peer.effectiveName()).isEqualTo("Bob");
    assertThat(peer.squadron()).isEqualTo(SQUADRON);
    assertThat(peer.squadrons()).containsExactly(SQUADRON);
  }

  @Test
  void usernameIsHiddenWhenADisplayNameIsSet() {
    assertThat(UserDtoRedaction.toPeerShape(full("bob", "Bob")).username()).isNull();
  }

  @Test
  void usernameStaysWhenNoDisplayNameIsSet() {
    assertThat(UserDtoRedaction.toPeerShape(full("bob", null)).username()).isEqualTo("bob");
    assertThat(UserDtoRedaction.toPeerShape(full("bob", " ")).username()).isEqualTo("bob");
  }
}
