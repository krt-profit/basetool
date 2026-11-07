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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

/** Where a P4K import run's patch version comes from (REQ-P4K-005, ADR-0335 §6). */
public enum P4kPatchVersionOrigin {

  /** {@code source.build.publicPatch}, the public patch the RSI Launcher logged. */
  PUBLIC_PATCH,

  /**
   * The version of an exact {@code sc-alpha-<major>.<minor>.<patch>} branch, a lower bound of the
   * public patch, shown as the branch.
   */
  BRANCH_LOWER_BOUND
}
