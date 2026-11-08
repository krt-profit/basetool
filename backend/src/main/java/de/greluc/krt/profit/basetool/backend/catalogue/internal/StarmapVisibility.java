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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;

/**
 * The visibility rule of the starmap (REQ-P4K-038): an object is visible when its star system
 * folder names a live-available UEX star system, {@code hideInStarmap} is {@code false} and it has
 * a position; an admin's override wins over the rule. An object the sweep stamped or an admin hid
 * through a review case is never visible.
 */
@Component
@RequiredArgsConstructor
public class StarmapVisibility {

  private final StarSystemRepository starSystemRepository;

  /**
   * Reads the normalised names of the live-available UEX star systems.
   *
   * @return the names, e.g. {@code stanton}
   */
  @NotNull
  @Unmodifiable
  public Set<String> liveSystems() {
    return starSystemRepository.findAll().stream()
        .filter(s -> Boolean.TRUE.equals(s.getIsAvailableLive()))
        .map(s -> StarmapNames.normalise(s.getName()))
        .filter(Objects::nonNull)
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Applies the rule to one object.
   *
   * @param object the object
   * @param liveSystems the normalised names of the live-available UEX star systems
   * @return whether the object is visible
   */
  public static boolean isVisible(@NotNull StarmapObject object, @NotNull Set<String> liveSystems) {
    if (object.isP4kRetired()) {
      return false;
    }
    StarmapVisibilityOverride override = object.getVisibilityOverride();
    if (override != null) {
      return switch (override) {
        case VISIBLE -> true;
        case HIDDEN -> false;
      };
    }
    String system = StarmapNames.normalise(object.getStarSystem());
    return Boolean.FALSE.equals(object.getHideInStarmap())
        && object.hasPosition()
        && system != null
        && liveSystems.contains(system);
  }
}
