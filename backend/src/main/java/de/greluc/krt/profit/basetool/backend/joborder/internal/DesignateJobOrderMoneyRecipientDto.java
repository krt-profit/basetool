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

import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Deliberately selects one requester recipient by holder, account or name.
 *
 * @param holderId an existing order holder
 * @param userId an explicitly selected active account
 * @param name a new accountless recipient's name
 * @param bankAccountId an existing Bank account deliberately selected for executed credit
 */
public record DesignateJobOrderMoneyRecipientDto(
    @Nullable UUID holderId,
    @Nullable UUID userId,
    @Nullable @Size(max = 200) String name,
    @Nullable UUID bankAccountId) {

  JobOrderMoneySelection selection() {
    return JobOrderMoneySelection.parse(holderId, userId, name);
  }
}
