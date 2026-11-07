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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One entry of {@code starmapObjects} in catalogue version 2 (contract, "Starmap objects"), bound
 * to the fields the import writes; every other field is skipped. The name is a game text, so it
 * never reaches the change report (REQ-P4K-033).
 *
 * @param guid the Record GUID
 * @param className the Record name
 * @param recordPath the record path without {@code .xml}
 * @param name the localised name, a game text
 * @param type the name of the object's {@code StarMapObjectType}
 * @param parentGuid the GUID of the parent object, or {@code null}
 * @param hideInStarmap the Record's flag, {@code null} when it states none
 * @param system the folder of the star system that places the object, or {@code null}
 * @param position {@code [x, y, z]} in metres in the system's frame, or {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record P4kStarmapObjectDto(
    @Nullable String guid,
    @Nullable String className,
    @Nullable String recordPath,
    @Nullable P4kLocalisedText name,
    @Nullable String type,
    @Nullable String parentGuid,
    @Nullable Boolean hideInStarmap,
    @Nullable String system,
    @Nullable List<Double> position)
    implements P4kRecord {

  /** The number of coordinates of a position. */
  private static final int AXES = 3;

  /**
   * Returns no name: the starmap name is a game text and stays out of every report row.
   *
   * @return {@code null}
   */
  @Override
  @Nullable
  public String reportName() {
    return null;
  }

  /**
   * Parses {@link #parentGuid()}.
   *
   * @return the parent's GUID, or {@code null} when absent or not a UUID
   */
  @Nullable
  public UUID parsedParentGuid() {
    return P4kGuids.parse(parentGuid);
  }

  /**
   * Returns one coordinate of a complete, finite position.
   *
   * @param axis {@code 0}, {@code 1} or {@code 2} for x, y and z
   * @return the coordinate, or {@code null} when the position is absent or incomplete
   */
  @Nullable
  public Double coordinate(int axis) {
    if (position == null || position.size() != AXES || position.contains(null)) {
      return null;
    }
    for (Double value : position) {
      if (!Double.isFinite(value)) {
        return null;
      }
    }
    return position.get(axis);
  }
}
