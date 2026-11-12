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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import de.greluc.krt.profit.basetool.backend.kernel.DomainProblem;
import org.springframework.http.HttpStatus;

/** Refuses a selected template whose scoped revision is no longer available. */
final class MissionTemplateStaleException extends DomainProblem {

  private static final String MESSAGE = "The selected mission template revision is unavailable";

  MissionTemplateStaleException() {
    super(MESSAGE);
  }

  MissionTemplateStaleException(Throwable cause) {
    super(MESSAGE, cause);
  }

  @Override
  public HttpStatus status() {
    return MissionProblemCode.MISSION_TEMPLATE_STALE.status();
  }

  @Override
  public String code() {
    return MissionProblemCode.MISSION_TEMPLATE_STALE.code();
  }

  @Override
  public String logLabel() {
    return "Mission template unavailable";
  }
}
