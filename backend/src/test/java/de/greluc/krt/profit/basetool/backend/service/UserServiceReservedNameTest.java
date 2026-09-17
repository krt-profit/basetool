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
 * Two display names a member must not be able to give themselves (REQ-SEC-062).
 *
 * <p>Both are about the Art. 17 erasure, whose text-matched statements are driven by exactly this
 * self-service field and carry no owner predicate:
 *
 * <ul>
 *   <li><b>The erasure sentinel.</b> {@code HandleAnonymisation}'s comment asserted that no real
 *       handle could equal the token while the field had only {@code @Size(max = 255)} on it — an
 *       invariant the code did not have. This class is what the comment now points at.
 *   <li><b>Another live account's name.</b> A departing member could set their display name to a
 *       victim's handle, tick "also erase my history", and have an admin rewrite the
 *       <em>victim's</em> job orders, handover receipts and audit labels to the sentinel. Since
 *       every viewer renders that token as "anonymised", the victim's rows then state that this
 *       person requested erasure — about somebody who never asked.
 * </ul>
 *
 * <p>What is <em>not</em> asserted here, deliberately: that the column is globally unique. Two
 * members who happen to share a spelling is a situation the system has always tolerated and
 * ADR-0183 documents as acceptable over-matching. What is rejected is <em>changing</em> a name into
 * a collision, which is the only way to aim the eraser.
 */
class UserServiceReservedNameTest {

  private static final UUID SELF = UUID.fromString("11111111-1111-1111-1111-111111111111");

  private UserRepository userRepository;
  private UserService service;
  private User self;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    // Only the collaborator these two paths touch; nothing else in the graph is reached, so the
    // rest is passed as null deliberately rather than mocked (backend/CLAUDE.md, test fixtures).
    service = new UserService(userRepository, null, null, null, null);
    self = new User();
    self.setId(SELF);
    self.setUsername("TheirOwnLogin");
    when(userRepository.findById(SELF)).thenReturn(Optional.of(self));
    when(userRepository.saveAndFlush(any(User.class))).thenAnswer(i -> i.getArgument(0));
  }

  // covers REQ-SEC-062 - the sentinel's uniqueness is enforced, not asserted in a comment
  @Test
  void theErasureSentinelIsRejected() {
    assertThatThrownBy(
            () -> service.updateUserDescription(SELF, null, HandleAnonymisation.SENTINEL, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reserved");
  }

  // covers REQ-SEC-062 - and case is not a way round it, because the erasure matches either way
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

  // covers REQ-SEC-062 - a member cannot aim the eraser at somebody else by taking their name
  @Test
  void anotherLiveAccountsNameIsRejected() {
    when(userRepository.existsOtherAccountWithName(eq("valkyrie"), eq(SELF))).thenReturn(true);

    assertThatThrownBy(() -> service.updateUserDescription(SELF, null, "Valkyrie", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("already in use");
  }

  // covers REQ-SEC-062 - the check is lower-cased, or it would be sidestepped by typing case
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

  // covers REQ-SEC-062 - clearing the field is not a collision with anything
  @Test
  void aBlankNameClearsTheFieldWithoutAskingTheDatabase() {
    assertThatCode(() -> service.updateUserDescription(SELF, null, "   ", null))
        .doesNotThrowAnyException();
    assertThat(self.getDisplayName()).isNull();
    verify(userRepository, org.mockito.Mockito.never()).existsOtherAccountWithName(any(), any());
  }
}
