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

package de.greluc.krt.profit.basetool.backend.privacy.internal;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.privacy.api.UserDeletionService;
import de.greluc.krt.profit.basetool.backend.privacy.api.UserDeletionService.KeycloakPresenceCheck;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes the account row of a registered deleted account again, which a restore or a re-created
 * account brought back (REQ-DATA-048); the caller removes the Keycloak user afterwards.
 */
@Component
@RequiredArgsConstructor
public class ErasureReapplication {

  private final UserRepository userRepository;
  private final UserDeletionService userDeletionService;

  /**
   * Deletes the account row of {@code userId} with every module's part, as the original deletion
   * did, when the row exists.
   *
   * <p>{@code inKeycloak} is flipped to {@code false} first because {@link UserDeletionService}
   * refuses an account the flag still claims is present; the caller deletes the Keycloak user once
   * this transaction committed.
   *
   * @param userId the registered id
   * @return {@code true} when a row existed and was deleted
   */
  @Transactional
  public boolean deleteAccountRow(@NotNull UUID userId) {
    if (!userRepository.existsById(userId)) {
      return false;
    }
    userDeletionService.prepareErasure(userId);
    Optional<User> user = userRepository.findById(userId);
    if (user.isEmpty()) {
      return false;
    }
    user.get().setInKeycloak(false);
    userRepository.saveAndFlush(user.get());
    userDeletionService.deleteUser(
        userId, KeycloakPresenceCheck.WAIVED_CALLER_REMOVES_THE_KEYCLOAK_USER);
    return true;
  }
}
