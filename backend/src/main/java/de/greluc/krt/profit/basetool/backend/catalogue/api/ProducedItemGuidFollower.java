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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Takes up the produced-item GUIDs a P4K APPLY wrote onto the blueprints (REQ-P4K-029, ADR-0343).
 * Implemented by a module that keys its rows by those GUIDs; the APPLY calls it inside its own
 * transaction after the reconcilers and the sweep, and puts what it reports on the review list; a
 * preview calls it too, to report the same review rows without writing.
 */
public interface ProducedItemGuidFollower {

  /**
   * Moves the module's rows onto the GUID keys the blueprints now carry, or, for a preview, only
   * reports what would stay.
   *
   * @param apply whether to move the rows; {@code false} for a preview, which writes nothing
   * @return the name keys that still resolve to no produced-item GUID or to several
   */
  @NotNull
  @Unmodifiable
  List<UnresolvedProductKey> followProducedItemGuids(boolean apply);

  /**
   * A name key that keeps its fallback because it resolves to no produced-item GUID or to several.
   *
   * @param nameKey the normalised product name the rows are keyed by
   * @param productName the product's display name
   * @param ambiguous {@code true} when the name resolves to several GUIDs, {@code false} for none
   */
  record UnresolvedProductKey(
      @NotNull String nameKey, @NotNull String productName, boolean ambiguous) {}
}
