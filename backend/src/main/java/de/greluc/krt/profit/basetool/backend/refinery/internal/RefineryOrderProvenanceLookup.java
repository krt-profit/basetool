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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderProvenanceDto;
import org.jetbrains.annotations.Nullable;

/** The refinery module's view of an order's provenance with its clients' display names. */
public interface RefineryOrderProvenanceLookup {

  /**
   * Returns the provenance of an order as the web shows it (REQ-REFINERY-027, REQ-REFINERY-028).
   *
   * @param order the order, or {@code null}
   * @return the provenance with each client named, or {@code null} for a {@code null} order
   */
  @Nullable
  RefineryOrderProvenanceDto provenance(@Nullable RefineryOrder order);
}
