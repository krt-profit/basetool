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
 * Mirror of where a member stands in the latest home-location phase (REQ-PATCH-021, REQ-PATCH-022),
 * labelled by {@code homeLocation.state.<STATE>}.
 *
 * <p>Must carry every backend constant under the same name, or deserialising a prompt fails.
 */
@DtoMirror
public enum MemberRelocationState {
  /** No phase asks the member: none ever ran, or the account is younger than its live time. */
  NOT_ASKED,
  /** The phase is open and the member has not chosen. */
  PENDING,
  /** The member has chosen and relocated in the phase. */
  RELOCATED,
  /** An admin ended the phase before the member chose. */
  PHASE_ENDED
}
