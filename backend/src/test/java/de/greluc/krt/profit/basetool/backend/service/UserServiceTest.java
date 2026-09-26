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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies that the in-place profile edits ({@code updateUserDescription}, {@code
 * updateUserDefaultPayoutPreference}, {@code updateUserShareBlueprintsGlobally}) persist with
 * {@code saveAndFlush}, so the response carries the incremented version and a consecutive edit does
 * not 409.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

  @Mock private UserRepository userRepository;
  @Mock private AuditService auditService;

  @InjectMocks private UserService userService;

  /**
   * Builds a managed-looking user with the given id and version 0, the starting state for an
   * in-place profile edit.
   *
   * @param id the user id
   * @return a user stamped with {@code id} and version 0
   */
  private User userWithId(UUID id) {
    User user = new User();
    user.setId(id);
    user.setVersion(0L);
    return user;
  }

  /**
   * Pins that {@code updateUserDescription} returns the user from a {@code saveAndFlush}, not a
   * plain {@code save}: the profile page writes the returned {@code @Version} back onto every
   * hidden version input in place via {@code syncAllVersions} (no reload), so a stale {@code save}
   * version would 409 the next consecutive profile edit.
   */
  @Test
  void updateUserDescription_flushesSoVersionIsFresh() {
    UUID id = UUID.randomUUID();
    User user = userWithId(id);
    when(userRepository.findById(id)).thenReturn(Optional.of(user));
    when(userRepository.saveAndFlush(user)).thenReturn(user);

    userService.updateUserDescription(id, "a new bio", "Display Name", 0L);

    verify(userRepository).saveAndFlush(user);
    verify(userRepository, never()).save(user);
  }

  /**
   * Pins that {@code updateUserDefaultPayoutPreference} returns the user from a {@code
   * saveAndFlush}, not a plain {@code save}: the profile payout-preference dropdown writes the
   * returned {@code @Version} back in place via {@code syncAllVersions} (no reload), so a stale
   * {@code save} version would 409 the next consecutive change.
   */
  @Test
  void updateUserDefaultPayoutPreference_flushesSoVersionIsFresh() {
    UUID id = UUID.randomUUID();
    User user = userWithId(id);
    when(userRepository.findById(id)).thenReturn(Optional.of(user));
    when(userRepository.saveAndFlush(user)).thenReturn(user);

    userService.updateUserDefaultPayoutPreference(id, PayoutPreference.DONATE, 0L);

    verify(userRepository).saveAndFlush(user);
    verify(userRepository, never()).save(user);
  }

  /**
   * Pins that {@code updateUserShareBlueprintsGlobally} sets the opt-in flag and returns the user
   * from a {@code saveAndFlush}, not a plain {@code save}: the profile blueprint-sharing toggle
   * writes the returned {@code @Version} back in place via {@code syncAllVersions} (no reload), so
   * a stale {@code save} version would 409 the next consecutive change (REQ-INV-018).
   */
  @Test
  void updateUserShareBlueprintsGlobally_setsFlagAndFlushesSoVersionIsFresh() {
    UUID id = UUID.randomUUID();
    User user = userWithId(id);
    when(userRepository.findById(id)).thenReturn(Optional.of(user));
    when(userRepository.saveAndFlush(user)).thenReturn(user);

    User result = userService.updateUserShareBlueprintsGlobally(id, true, 0L);

    assertTrue(result.isShareBlueprintsGlobally());
    verify(userRepository).saveAndFlush(user);
    verify(userRepository, never()).save(user);
    verify(auditService)
        .record(eq(AuditEventType.BLUEPRINT_SHARING_CHANGED), isNull(), isNull(), eq(id), any());
  }

  /** Pins that re-saving the same sharing value writes no audit event (REQ-AUDIT-001). */
  @Test
  void updateUserShareBlueprintsGlobally_withUnchangedValue_recordsNothing() {
    UUID id = UUID.randomUUID();
    User user = userWithId(id);
    when(userRepository.findById(id)).thenReturn(Optional.of(user));
    when(userRepository.saveAndFlush(user)).thenReturn(user);

    userService.updateUserShareBlueprintsGlobally(id, false, 0L);

    verifyNoInteractions(auditService);
  }

  /**
   * Pins the optimistic-lock contract of {@code updateUserShareBlueprintsGlobally}: a stale {@code
   * version} surfaces as {@code ObjectOptimisticLockingFailureException} (HTTP 409) instead of a
   * silent overwrite, and no write is attempted.
   */
  @Test
  void updateUserShareBlueprintsGlobally_staleVersion_throwsAndDoesNotSave() {
    UUID id = UUID.randomUUID();
    User user = userWithId(id);
    when(userRepository.findById(id)).thenReturn(Optional.of(user));

    assertThrows(
        org.springframework.orm.ObjectOptimisticLockingFailureException.class,
        () -> userService.updateUserShareBlueprintsGlobally(id, true, 1L));

    verify(userRepository, never()).saveAndFlush(any());
    verify(userRepository, never()).save(any());
  }
}
