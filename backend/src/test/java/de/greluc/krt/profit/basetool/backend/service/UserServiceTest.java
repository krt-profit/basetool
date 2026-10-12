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

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditService;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies that the in-place profile edits ({@code updateUserDescription}) persist with {@code
 * saveAndFlush}, so the response carries the incremented version and a consecutive edit does not
 * 409.
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
}
