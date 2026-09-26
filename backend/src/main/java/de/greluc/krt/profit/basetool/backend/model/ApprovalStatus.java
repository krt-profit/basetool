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
 * Account approval lifecycle of a {@link User} (REQ-SEC-017).
 *
 * <p>A new Discord registration starts {@link #PENDING} with no authorities until an admin moves it
 * to {@link #ACTIVE} or {@link #REJECTED}; credential- and admin-created users are {@code ACTIVE}.
 */
public enum ApprovalStatus {

  /**
   * Awaiting an admin decision; the user has no authorities (only {@code ROLE_PENDING_APPROVAL}).
   */
  PENDING,

  /** Approved (or never required approval); authorities are assembled normally. */
  ACTIVE,

  /** Rejected by an admin; treated like {@link #PENDING} — no authorities are granted. */
  REJECTED
}
