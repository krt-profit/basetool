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

package de.greluc.krt.profit.basetool.backend.exception;

/**
 * Thrown when a refinery order is linked to a mission its owner does not take part in
 * (REQ-SEC-042).
 *
 * <p>Answers {@code 400} with code {@code MISSION_PARTICIPANT_REQUIRED}; it is a validation
 * failure, not a permission failure, and applies to managers too.
 */
public final class MissionParticipantRequiredException extends AppException {

  /**
   * Creates the exception. The message is the i18n key of the localized detail, so the RFC 7807
   * {@code detail} reaches every client — the web frontend and the Android app alike — in the
   * caller's language.
   */
  public MissionParticipantRequiredException() {
    super(
        AppExceptionKind.MISSION_PARTICIPANT_REQUIRED,
        AppExceptionKind.MISSION_PARTICIPANT_REQUIRED.detailKey());
  }
}
