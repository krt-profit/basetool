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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.HandleAnonymisation;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the two display names a member must not give themselves (REQ-SEC-062): the erasure
 * sentinel, and another live account's name, which would aim the Art. 17 history erasure at that
 * account's rows.
 *
 * <p>Global uniqueness is not asserted; only changing a name into a collision is rejected
 * (ADR-0183).
 */
class UserServiceReservedNameTest {

  private static final UUID SELF = UUID.fromString("11111111-1111-1111-1111-111111111111");

  private UserRepository userRepository;
  private UserService service;
  private User self;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    service = new UserService(userRepository, null, null, null, null, null);
    self = new User();
    self.setId(SELF);
    self.setUsername("TheirOwnLogin");
    when(userRepository.findById(SELF)).thenReturn(Optional.of(self));
    when(userRepository.saveAndFlush(any(User.class))).thenAnswer(i -> i.getArgument(0));
  }

  @Test
  void theErasureSentinelIsRejected() {
    assertThatThrownBy(
            () -> service.updateUserDescription(SELF, null, HandleAnonymisation.SENTINEL, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reserved");
  }

  @Test
  void theSentinelIsRejectedInAnyCase() {
    assertThatThrownBy(
            () ->
                service.updateUserDescription(
                    SELF, null, HandleAnonymisation.SENTINEL.toLowerCase(Locale.ROOT), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                service.updateUserDescription(
                    SELF, null, "  " + HandleAnonymisation.SENTINEL + "  ", null))
        .as("trimmed before the comparison")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anotherLiveAccountsNameIsRejected() {
    when(userRepository.existsOtherAccountWithName(eq("valkyrie"), eq(SELF))).thenReturn(true);

    assertThatThrownBy(() -> service.updateUserDescription(SELF, null, "Valkyrie", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("already in use");
  }

  @Test
  void theCollisionCheckIsCaseInsensitive() {
    when(userRepository.existsOtherAccountWithName(any(), any())).thenReturn(false);

    service.updateUserDescription(SELF, null, "MiXeDcAsE", null);

    verify(userRepository).existsOtherAccountWithName("mixedcase", SELF);
  }

  @Test
  void aFreeNameIsAccepted() {
    when(userRepository.existsOtherAccountWithName(any(), any())).thenReturn(false);

    assertThatCode(() -> service.updateUserDescription(SELF, null, "NobodyElse", null))
        .doesNotThrowAnyException();
    assertThat(self.getDisplayName()).isEqualTo("NobodyElse");
  }

  @Test
  void aBlankNameClearsTheFieldWithoutAskingTheDatabase() {
    assertThatCode(() -> service.updateUserDescription(SELF, null, "   ", null))
        .doesNotThrowAnyException();
    assertThat(self.getDisplayName()).isNull();
    verify(userRepository, org.mockito.Mockito.never()).existsOtherAccountWithName(any(), any());
  }
}
