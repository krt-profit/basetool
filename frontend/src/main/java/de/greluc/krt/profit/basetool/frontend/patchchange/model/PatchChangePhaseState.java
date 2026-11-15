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

package de.greluc.krt.profit.basetool.frontend.patchchange.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;

/**
 * Mirror of where a patch change's home-location phase stands (REQ-PATCH-024), labelled by {@code
 * admin.patchChange.phase.<STATE>}.
 *
 * <p>Must carry every backend constant under the same name, or deserialising the history fails.
 */
@DtoMirror
public enum PatchChangePhaseState {
  /** Members are asked; the latest patch change with the prompt on, not ended. */
  OPEN,
  /** An admin ended it early. */
  ENDED,
  /** A later patch change with the prompt on took it over. */
  SUPERSEDED
}
