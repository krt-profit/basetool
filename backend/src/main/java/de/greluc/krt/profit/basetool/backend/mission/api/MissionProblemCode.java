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

package de.greluc.krt.profit.basetool.backend.mission.api;

import de.greluc.krt.profit.basetool.backend.kernel.ProblemCode;
import org.springframework.http.HttpStatus;

/** Stable problem codes returned by mission planning. */
public enum MissionProblemCode implements ProblemCode {

  /** The selected template revision is unavailable to the mission draft. */
  MISSION_TEMPLATE_STALE;

  /**
   * Returns the wire code for a mission planning failure.
   *
   * @return the constant name sent as the problem code
   */
  @Override
  public String code() {
    return name();
  }

  /**
   * Returns the conflict status for an unavailable template revision.
   *
   * @return the HTTP status carried by the problem
   */
  @Override
  public HttpStatus status() {
    return HttpStatus.CONFLICT;
  }
}
