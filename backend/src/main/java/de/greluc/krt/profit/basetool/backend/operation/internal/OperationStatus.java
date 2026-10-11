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

package de.greluc.krt.profit.basetool.backend.operation.internal;

/** Enumeration of Operation Status values. */
public enum OperationStatus {
  PLANNED,
  ACTIVE,
  COMPLETED,
  CANCELED;

  /**
   * Project-wide state machine for an operation's lifecycle. ADMIN callers bypass this gate at the
   * controller boundary; every other caller must respect it.
   */
  public boolean canTransitionTo(OperationStatus next) {
    if (this == next) {
      return true;
    }
    return switch (this) {
      case PLANNED -> next == ACTIVE || next == CANCELED;
      case ACTIVE -> next == COMPLETED || next == CANCELED;
      case COMPLETED, CANCELED -> false;
    };
  }
}
