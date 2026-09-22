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
 * A refinery order is being linked to a mission its owner does not take part in (REQ-SEC-042).
 *
 * <p>Answers {@code 400} with the stable problem code {@code MISSION_PARTICIPANT_REQUIRED}. The
 * link feeds the mission's operation payout: {@code OperationPayoutCalculator} adds every linked
 * order's result to the pool and credits its expenses to the order's owner. An order whose owner is
 * not on the mission would move that pool without its owner ever being part of the payout, so the
 * link is refused — with no exception for managers, by the owner's decision of 2026-09-22.
 *
 * <p>It is deliberately <em>not</em> a permission failure: the caller may edit the order, the
 * mission choice is simply not valid for its owner. The own type exists so the frontend can name
 * the mission field instead of the generic "invalid material" message it shows for other 400s.
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
