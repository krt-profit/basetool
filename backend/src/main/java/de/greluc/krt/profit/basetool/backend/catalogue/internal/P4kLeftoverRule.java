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

/**
 * The rule by which a P4K APPLY retires a leftover material, named in the audit event's {@code
 * rule} detail.
 */
enum P4kLeftoverRule {

  /** A commodity an earlier seeding created that no rule would create any more (REQ-P4K-076). */
  SEEDED_NOT_CREATABLE,

  /** An admin's identity-less twin holding the P4K name of a matched material (REQ-P4K-077). */
  MANUAL_TWIN,

  /** An invisible Wiki-only twin of the material an alias names (REQ-P4K-078). */
  WIKI_TWIN
}
