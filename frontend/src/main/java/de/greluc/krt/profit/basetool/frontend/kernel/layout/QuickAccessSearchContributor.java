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

package de.greluc.krt.profit.basetool.frontend.kernel.layout;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * Finds the entities of one type for the quick access's typed search, scoped by the backend read of
 * the page the hits open (REQ-UI-033, ADR-0282, ADR-0297).
 *
 * <p>The sibling seam of {@link QuickAccessContributor}: it takes the search term. The term goes to
 * the backend as a query parameter only, never into a path, a log line or the MDC.
 */
public interface QuickAccessSearchContributor {

  /**
   * The type this contributor searches.
   *
   * @return the type
   */
  @NotNull
  QuickAccessSearchKind kind();

  /**
   * The entities of this type the caller may open whose name matches the term.
   *
   * <p>A failed read throws; the caller turns it into no hits for this type.
   *
   * @param query the trimmed search term, at least two characters
   * @param limit the most hits to return
   * @return at most {@code limit} hits in the backend's order, possibly empty
   */
  @NotNull
  List<QuickAccessHit> results(@NotNull String query, int limit);
}
