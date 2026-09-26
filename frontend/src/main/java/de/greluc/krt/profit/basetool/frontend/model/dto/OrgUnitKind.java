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

package de.greluc.krt.profit.basetool.frontend.model.dto;

/**
 * Frontend mirror of the backend {@code OrgUnitKind}: the four kinds of {@code org_unit} rows.
 *
 * <p>Must carry every backend constant under the same name, or deserialising a response that
 * contains it fails.
 */
public enum OrgUnitKind {
  /** Staffel — the original tenant kind that has driven the multi-tenancy work since Phase 1. */
  SQUADRON,

  /** Spezialkommando — the second tenant kind introduced by the Spezialkommando R2.a slice. */
  SPECIAL_COMMAND,

  /** Bereich — the area tier above Staffeln and Spezialkommandos. */
  BEREICH,

  /** Organisationsleitung — the top tier above every Bereich. */
  ORGANISATIONSLEITUNG
}
