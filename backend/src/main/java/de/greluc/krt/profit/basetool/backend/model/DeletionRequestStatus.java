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

package de.greluc.krt.profit.basetool.backend.model;

/**
 * The lifecycle of a member's Art. 17 erasure request (REQ-SEC-061).
 *
 * <p><b>There is deliberately no {@code EXECUTED} value.</b> Carrying the request out deletes the
 * {@code app_user} row, and {@code deletion_request.user_id} is {@code ON DELETE CASCADE}, so the
 * request goes with the account — which is the point of an erasure. The record that the deletion
 * happened is the {@code USER_DELETED} audit event, not a surviving row about a member who asked to
 * be forgotten.
 */
public enum DeletionRequestStatus {

  /**
   * Raised by the member and awaiting an admin's decision. At most one such row exists per member,
   * enforced by a partial unique index rather than by application code.
   */
  PENDING,

  /**
   * Taken back by the member before it was decided. Kept rather than deleted so the queue's history
   * reads truthfully: "this member asked and changed their mind" is a different fact from "this
   * member never asked", and it is the difference an admin needs when a second request arrives.
   */
  WITHDRAWN,

  /**
   * Refused by an admin, with the reasoning recorded in {@code decision_note}. The note is
   * mandatory in this state, DB-enforced: Art. 12(4) requires telling the requester <em>why</em> a
   * request is refused, and a reason nobody wrote down cannot be told to them.
   */
  DECLINED
}
