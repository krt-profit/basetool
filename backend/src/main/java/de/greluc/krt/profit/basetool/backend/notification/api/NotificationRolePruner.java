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

package de.greluc.krt.profit.basetool.backend.notification.api;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Clears notices whose original role-dependent delivery paths are withdrawn. */
@Service
@RequiredArgsConstructor
public class NotificationRolePruner {
  private final NotificationRepository repository;
  private final NotificationFanout fanout;

  /**
   * Clears a member's ineligible notices inside the role-change transaction.
   *
   * @param recipient the member whose stored roles changed
   * @param member whether the recipient still holds KRT Member
   * @param bankStaff whether the recipient still qualifies as bank staff
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void prune(@NotNull UUID recipient, boolean member, boolean bankStaff) {
    Set<NotificationRoleAudience> withdrawn =
        Arrays.stream(NotificationRoleAudience.values())
            .filter(audience -> !audience.admits(member, bankStaff))
            .collect(Collectors.toUnmodifiableSet());
    if (!withdrawn.isEmpty() && repository.deleteWithdrawnAudience(recipient, withdrawn) > 0) {
      TransactionSynchronizationManager.registerSynchronization(new InboxChange(fanout, recipient));
    }
  }

  /** Signals a committed inbox change. */
  private record InboxChange(NotificationFanout fanout, UUID recipient)
      implements TransactionSynchronization {
    @Override
    public void afterCommit() {
      fanout.publish(Set.of(recipient));
    }
  }
}
