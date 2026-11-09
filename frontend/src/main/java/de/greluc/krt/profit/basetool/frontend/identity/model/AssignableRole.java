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

package de.greluc.krt.profit.basetool.frontend.identity.model;

/**
 * The four application roles the Basetool assigns and withdraws itself (REQ-SEC-088), in display
 * order; the constant names are the backend's role codes.
 */
public enum AssignableRole {

  /** Full administration. */
  ADMIN,

  /** Organisation member. */
  KRT_MEMBER,

  /** Bank staff. */
  BANK_EMPLOYEE,

  /** Bank management. */
  BANK_MANAGEMENT
}
