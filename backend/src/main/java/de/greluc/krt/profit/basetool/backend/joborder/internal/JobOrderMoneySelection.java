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
import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * How a caller selected the credited party, independently of later display and link changes.
 *
 * @param kind the distinct choice made in the form
 * @param reference explicitly selected holder or account, when applicable
 * @param name normalized name of a new accountless party, when applicable
 */
record JobOrderMoneySelection(Kind kind, @Nullable UUID reference, @Nullable String name) {

  /** Distinct durable provision selection modes. */
  enum Kind {
    SELF,
    EXISTING,
    LINKED,
    ACCOUNTLESS
  }

  static JobOrderMoneySelection parse(
      @Nullable UUID holderId, @Nullable UUID userId, @Nullable String submittedName) {
    if ((holderId != null && (userId != null || submittedName != null))
        || (userId != null && submittedName != null)) {
      throw new BadRequestException("orders.money.holder.selection.invalid");
    }
    if (holderId != null) {
      return new JobOrderMoneySelection(Kind.EXISTING, holderId, null);
    }
    if (userId != null) {
      return new JobOrderMoneySelection(Kind.LINKED, userId, null);
    }
    if (submittedName != null) {
      String name = StringNormalization.trimToNull(submittedName);
      if (name == null || HandleAnonymisation.isReserved(name)) {
        throw new BadRequestException("orders.money.holder.selection.invalid");
      }
      return new JobOrderMoneySelection(Kind.ACCOUNTLESS, null, name);
    }
    return new JobOrderMoneySelection(Kind.SELF, null, null);
  }
}
