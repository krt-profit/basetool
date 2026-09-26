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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** Verifies how {@link UserService#updateUserRsiHandle} stores, clears and refuses a handle. */
@ExtendWith(MockitoExtension.class)
class UserServiceRsiHandleTest {

  @Mock private UserRepository userRepository;

  @InjectMocks private UserService userService;

  /**
   * Builds a user with the given id at version 0.
   *
   * @param id the user id
   * @return the user
   */
  private static User userWithId(UUID id) {
    User user = new User();
    user.setId(id);
    user.setVersion(0L);
    return user;
  }

  @Test
  void storesTheTrimmedHandleWhenNobodyElseAnswersToIt() {
    UUID id = UUID.randomUUID();
    User user = userWithId(id);
    when(userRepository.findById(id)).thenReturn(Optional.of(user));
    when(userRepository.existsOtherAccountWithName("some_handle-1", id)).thenReturn(false);
    when(userRepository.saveAndFlush(user)).thenReturn(user);

    User saved = userService.updateUserRsiHandle(id, "  Some_Handle-1 ", 0L);

    assertThat(saved.getRsiHandle()).isEqualTo("Some_Handle-1");
    verify(userRepository).saveAndFlush(user);
  }

  @Test
  void aBlankHandleClearsTheFieldWithoutACollisionCheck() {
    UUID id = UUID.randomUUID();
    User user = userWithId(id);
    user.setRsiHandle("Old_Handle");
    when(userRepository.findById(id)).thenReturn(Optional.of(user));
    when(userRepository.saveAndFlush(user)).thenReturn(user);

    userService.updateUserRsiHandle(id, "   ", 0L);

    assertThat(user.getRsiHandle()).isNull();
    verify(userRepository, never()).existsOtherAccountWithName(any(), any());
  }

  @Test
  void aNullHandleClearsTheField() {
    UUID id = UUID.randomUUID();
    User user = userWithId(id);
    user.setRsiHandle("Old_Handle");
    when(userRepository.findById(id)).thenReturn(Optional.of(user));
    when(userRepository.saveAndFlush(user)).thenReturn(user);

    userService.updateUserRsiHandle(id, null, 0L);

    assertThat(user.getRsiHandle()).isNull();
  }

  @Test
  void refusesAHandleOutsideTheRsiShape() {
    UUID id = UUID.randomUUID();
    User user = userWithId(id);
    when(userRepository.findById(id)).thenReturn(Optional.of(user));

    for (String bad : new String[] {"ab", "has space", "Umlautä", "#ANONYMISED#", "x".repeat(61)}) {
      assertThatThrownBy(() -> userService.updateUserRsiHandle(id, bad, 0L))
          .as(bad)
          .isInstanceOf(BadRequestException.class)
          .hasMessage("error.user.rsiHandle.invalid");
    }
    verify(userRepository, never()).saveAndFlush(any());
  }

  @Test
  void refusesAHandleAnotherAccountAnswersToWithoutNamingIt() {
    UUID id = UUID.randomUUID();
    User user = userWithId(id);
    when(userRepository.findById(id)).thenReturn(Optional.of(user));
    when(userRepository.existsOtherAccountWithName("taken_handle", id)).thenReturn(true);

    assertThatThrownBy(() -> userService.updateUserRsiHandle(id, "Taken_Handle", 0L))
        .isInstanceOf(DuplicateEntityException.class)
        .hasMessage("error.user.rsiHandle.taken")
        .message()
        .doesNotContainIgnoringCase("taken_handle");
    verify(userRepository, never()).saveAndFlush(any());
  }

  @Test
  void aConcurrentClaimOfTheSameHandleSurfacesAsTaken() {
    UUID id = UUID.randomUUID();
    User user = userWithId(id);
    when(userRepository.findById(id)).thenReturn(Optional.of(user));
    when(userRepository.existsOtherAccountWithName("race_handle", id)).thenReturn(false);
    when(userRepository.saveAndFlush(user))
        .thenThrow(new DataIntegrityViolationException("ux_app_user_rsi_handle_lower"));

    assertThatThrownBy(() -> userService.updateUserRsiHandle(id, "Race_Handle", 0L))
        .isInstanceOf(DuplicateEntityException.class)
        .hasMessage("error.user.rsiHandle.taken");
  }

  @Test
  void aStaleVersionIsRefused() {
    UUID id = UUID.randomUUID();
    User user = userWithId(id);
    user.setVersion(3L);
    when(userRepository.findById(id)).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> userService.updateUserRsiHandle(id, "Some_Handle", 1L))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    verify(userRepository, never()).saveAndFlush(any());
  }
}
