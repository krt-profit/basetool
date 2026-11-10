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

package de.greluc.krt.profit.basetool.backend.inventory.api;

import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Tracks unverifiable restored quantity by its real journal action without claiming a physical
 * origin.
 *
 * @param journalReference the immutable server journal entry that requires the quantity restore
 * @param fromQuanta the inclusive offset within that journal reference
 * @param toQuanta the exclusive offset within that journal reference
 */
public record LegacyJournalQuantityReference(
    @NotNull UUID journalReference, long fromQuanta, long toQuanta)
    implements StockQuantityEvidence {
  /** Rejects missing references and empty or negative reference intervals. */
  public LegacyJournalQuantityReference {
    Objects.requireNonNull(journalReference);
    if (fromQuanta < 0L || toQuanta <= fromQuanta) {
      throw new IllegalArgumentException("A journal quantity reference must be positive");
    }
  }

  @Override
  public long quanta() {
    return toQuanta - fromQuanta;
  }
}
