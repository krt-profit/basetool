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

import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Deliberate correction of an order-local holder identity.
 *
 * @param kind the exact identity change to record
 * @param version observed revision of the original holder
 * @param name corrected accountless name, when selected
 * @param userId consciously selected active account, when applicable
 * @param targetHolderId survivor of an explicit same-person merge
 * @param targetVersion observed survivor revision
 */
public record JobOrderMoneyHolderIdentityDto(
    @NotNull Kind kind,
    @NotNull @Positive Long version,
    @Nullable @Size(max = 200) String name,
    @Nullable UUID userId,
    @Nullable UUID targetHolderId,
    @Nullable @Positive Long targetVersion) {

  /** The distinct historical reasons a holder's current identity can change. */
  public enum Kind {
    NAME,
    LINK,
    MERGE,
    CORRECT_LINK
  }

  JobOrderMoneySelection selection(UUID holderId) {
    boolean hasTarget = targetHolderId != null || targetVersion != null;
    boolean valid =
        switch (kind) {
          case NAME -> name != null && userId == null && !hasTarget;
          case LINK -> name == null && userId != null && !hasTarget;
          case MERGE ->
              name == null
                  && userId == null
                  && targetHolderId != null
                  && !targetHolderId.equals(holderId)
                  && targetVersion != null;
          case CORRECT_LINK -> (name == null) != (userId == null) && !hasTarget;
        };
    if (!valid) {
      throw new BadRequestException("orders.money.holder.identity.selection");
    }
    return JobOrderMoneySelection.parse(targetHolderId, userId, name);
  }
}
