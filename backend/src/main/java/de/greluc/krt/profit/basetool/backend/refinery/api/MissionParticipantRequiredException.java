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

package de.greluc.krt.profit.basetool.backend.refinery.api;

import de.greluc.krt.profit.basetool.backend.exception.DomainProblem;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a refinery order is linked to a mission its owner does not take part in
 * (REQ-SEC-042).
 *
 * <p>Answers {@code 400} with code {@code MISSION_PARTICIPANT_REQUIRED}; it is a validation
 * failure, not a permission failure, and applies to managers too.
 */
public final class MissionParticipantRequiredException extends DomainProblem {

  /**
   * Creates the exception. The message is the i18n key of the localized detail, so the RFC 7807
   * {@code detail} reaches every client — the web frontend and the Android app alike — in the
   * caller's language.
   */
  public MissionParticipantRequiredException() {
    super("problem.mission_participant_required.detail");
  }

  /**
   * The status the refusal is answered with.
   *
   * @return {@code 400}
   */
  @NotNull
  @Override
  public HttpStatus status() {
    return RefineryProblemCode.MISSION_PARTICIPANT_REQUIRED.status();
  }

  /**
   * The stable code of the refusal.
   *
   * @return {@code MISSION_PARTICIPANT_REQUIRED}
   */
  @NotNull
  @Override
  public String code() {
    return RefineryProblemCode.MISSION_PARTICIPANT_REQUIRED.code();
  }

  /**
   * The label the handler's log line names the refusal by.
   *
   * @return {@code "Mission participant required"}
   */
  @NotNull
  @Override
  public String logLabel() {
    return "Mission participant required";
  }
}
