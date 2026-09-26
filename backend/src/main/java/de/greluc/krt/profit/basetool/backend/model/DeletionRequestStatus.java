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
 * The lifecycle of a member's Art. 17 erasure request (REQ-SEC-061). There is no executed state:
 * carrying the request out deletes it together with the account.
 */
public enum DeletionRequestStatus {

  /**
   * Raised by the member and awaiting an admin's decision. At most one such row exists per member,
   * enforced by a partial unique index rather than by application code.
   */
  PENDING,

  /**
   * Taken back by the member before it was decided; kept so the queue shows that the member asked
   * and changed their mind.
   */
  WITHDRAWN,

  /**
   * Refused by an admin, with the reasoning recorded in {@code decision_note}. The note is
   * mandatory in this state, DB-enforced: Art. 12(4) requires telling the requester <em>why</em> a
   * request is refused, and a reason nobody wrote down cannot be told to them.
   */
  DECLINED
}
