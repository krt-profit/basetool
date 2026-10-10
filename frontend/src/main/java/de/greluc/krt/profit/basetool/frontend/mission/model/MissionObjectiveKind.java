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

package de.greluc.krt.profit.basetool.frontend.mission.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;

/**
 * Frontend mirror of the backend's {@code MissionObjectiveKind} — the classification of a mission
 * goal (Ziel). Drives the grouped overview display (Hauptziel -&gt; Nebenziel -&gt; Nicht-Ziel) and
 * the per-row selector in the goals editor. The localized German labels come from the message
 * bundle (key {@code mission.objective.kind.*}).
 */
@DtoMirror
public enum MissionObjectiveKind {

  /** A primary objective ("Hauptziel") — a core, must-achieve goal of the mission. */
  PRIMARY,

  /** A secondary objective ("Nebenziel") — a desirable but non-essential goal. */
  SECONDARY,

  /** An explicit non-goal ("Nicht-Ziel") — something the mission deliberately does NOT pursue. */
  NON_GOAL
}
