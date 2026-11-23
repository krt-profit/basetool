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

package de.greluc.krt.profit.basetool.backend.joborder.api;

/**
 * Names the authority that decides for which units a caller writes a job order; the commands take
 * it as a parameter and resolve it on every call (ADR-0405).
 */
public enum JobOrderWriterRule {

  /**
   * The caller's contextual Logistician authorities inside the active org-unit pin, for either side
   * of an order; an administrator writes every unit unpinned and the pinned unit pinned
   * (REQ-ORDERS-053).
   */
  CONTEXTUAL,

  /**
   * The units the actor's member directly belongs to and is a Logistician of by the org-stock rule
   * (the flag, a leadership rank, or Bereich or Organisationsleitung reach; {@code KRT Member};
   * active units), read from the database; requesting side only, and an administrator is a plain
   * member (REQ-XCH-009).
   */
  DIRECT_LOGISTICIAN;

  /**
   * Tells whether a writer of an order's responsible unit counts under this rule.
   *
   * @return {@code true} when either side's writer may write, {@code false} when only the
   *     requesting side counts
   */
  public boolean coversResponsibleSide() {
    return switch (this) {
      case CONTEXTUAL -> true;
      case DIRECT_LOGISTICIAN -> false;
    };
  }
}
