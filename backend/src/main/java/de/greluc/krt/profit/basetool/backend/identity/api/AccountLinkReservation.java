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

import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reserves the participants of a Discord link move through its final Keycloak write. */
@Service
@RequiredArgsConstructor
public class AccountLinkReservation {

  private final UserRepository userRepository;

  /**
   * Reserves both account ids in a stable order until the caller's transaction ends.
   *
   * @param sourceId the account whose Discord identity is being moved
   * @param targetId the surviving account
   * @throws BusinessConflictException if either account is already reserved
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void reserve(@NotNull UUID sourceId, @NotNull UUID targetId) {
    boolean sourceFirst = sourceId.compareTo(targetId) <= 0;
    UUID first = sourceFirst ? sourceId : targetId;
    UUID second = sourceFirst ? targetId : sourceId;
    if (!userRepository.tryReserveDiscordLinkAccount(first)
        || (!first.equals(second) && !userRepository.tryReserveDiscordLinkAccount(second))) {
      throw new BusinessConflictException("An account is already being consolidated");
    }
  }
}
