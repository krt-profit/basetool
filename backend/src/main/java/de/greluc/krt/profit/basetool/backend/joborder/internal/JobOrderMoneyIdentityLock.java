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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates monetary account links with account merge and erasure before order locks. */
@Component
@RequiredArgsConstructor
class JobOrderMoneyIdentityLock {

  private final JobOrderMoneyHolderRepository holders;

  @Transactional(propagation = Propagation.MANDATORY)
  void lockAccounts(UUID accountId, @Nullable UUID otherAccountId) {
    int accountKey = key(accountId);
    int otherKey = otherAccountId == null ? accountKey : key(otherAccountId);
    holders.lockAccountIdentity(Math.min(accountKey, otherKey));
    if (accountKey != otherKey) {
      holders.lockAccountIdentity(Math.max(accountKey, otherKey));
    }
  }

  private static int key(UUID accountId) {
    return Long.hashCode(accountId.getMostSignificantBits())
        ^ Long.hashCode(accountId.getLeastSignificantBits());
  }
}
