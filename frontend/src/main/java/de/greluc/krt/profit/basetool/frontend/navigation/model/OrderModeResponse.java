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

package de.greluc.krt.profit.basetool.frontend.navigation.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * The stored order of one navigation mode as the backend sends it (REQ-UI-032).
 *
 * @param scopes the moved keys by scope: {@code groups} for the groups of the mode, a group key for
 *     that group's links; a scope the member never moved is absent
 * @param version the mode's optimistic-lock version, echoed on the next write
 */
@DtoMirror
public record OrderModeResponse(@NotNull Map<String, List<String>> scopes, long version) {

  /** The order of a member who moved nothing. */
  public static final OrderModeResponse EMPTY = new OrderModeResponse(Map.of(), 0);

  /**
   * Copies the scopes so the record stays immutable and keeps their order.
   *
   * @param scopes the moved keys by scope
   * @param version the mode's version
   */
  public OrderModeResponse {
    Map<String, List<String>> copy = new LinkedHashMap<>();
    if (scopes != null) {
      scopes.forEach(
          (scope, keys) -> copy.put(scope, keys == null ? List.of() : List.copyOf(keys)));
    }
    scopes = Collections.unmodifiableMap(copy);
  }
}
