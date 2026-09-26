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
 * Discriminator for {@link OrgUnit}: which kind of tenant a row in the {@code org_unit} table
 * represents.
 *
 * <p>The names are referenced literally by database CHECK constraints, triggers and the
 * {@code @DiscriminatorValue}s, so a rename needs a coordinated migration. Only {@link #SQUADRON}
 * may carry the promotion subsystem.
 */
public enum OrgUnitKind {

  /** The squadron (Staffel) tenant; a user belongs to at most one. */
  SQUADRON,

  /**
   * The Spezialkommando tenant — a cross-cutting unit that members may join on top of their
   * Squadron membership, or instead of one. Mapped to {@code org_unit.kind = 'SPECIAL_COMMAND'} via
   * {@code @DiscriminatorValue} on {@link SpecialCommand}. Permanently barred from the promotion
   * subsystem by the database CHECK constraint introduced in V94.
   */
  SPECIAL_COMMAND,

  /**
   * The Bereich tenant, one level above Staffeln and Spezialkommandos (REQ-ORG-014), mapped to
   * {@link Bereich}; groups its child units and never carries promotion.
   */
  BEREICH,

  /**
   * The Organisationsleitung tenant at the top of the hierarchy (REQ-ORG-014), mapped to {@link
   * Organisationsleitung}; has no parent and never carries promotion.
   */
  ORGANISATIONSLEITUNG
}
