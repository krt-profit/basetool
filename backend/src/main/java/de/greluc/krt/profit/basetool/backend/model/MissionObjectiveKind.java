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

import lombok.RequiredArgsConstructor;

/**
 * Classification of a mission goal (Ziel). The three kinds drive the grouped read-only display on
 * the mission overview — primary goals first, then secondary goals, then explicit non-goals — and
 * the kind is the only goal attribute (besides ids and counts) that may appear in the audit trail,
 * since it is a non-personal enum whereas the goal title is user free text.
 */
@RequiredArgsConstructor
public enum MissionObjectiveKind {

  /** A primary objective ("Hauptziel") — a core, must-achieve goal of the mission. */
  PRIMARY(0),

  /** A secondary objective ("Nebenziel") — a desirable but non-essential goal. */
  SECONDARY(1),

  /**
   * An explicit non-goal ("Nicht-Ziel") — something the mission deliberately does NOT pursue,
   * stated to set the boundaries of the operation.
   */
  NON_GOAL(2);

  /** Zero-based grouping rank used to order the kinds on the overview (lower sorts first). */
  private final int rank;

  /**
   * Returns the grouping rank used to order goals on the mission overview: primary goals (0) before
   * secondary goals (1) before non-goals (2).
   *
   * @return the zero-based group rank
   */
  public int rank() {
    return rank;
  }
}
