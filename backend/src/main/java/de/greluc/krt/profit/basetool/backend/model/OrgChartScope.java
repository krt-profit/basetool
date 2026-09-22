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
 * The three places a {@link OrgChartPosition} can live in the Profit-Bereich org chart. Every
 * {@link OrgChartPositionType} belongs to exactly one scope (see {@link
 * OrgChartPositionType#scope()}), which decides whether the position carries an {@code org_unit_id}
 * and which validation rules apply in {@code OrgChartService}.
 */
public enum OrgChartScope {

  /**
   * The singleton area leadership at the top of the chart (Bereichsleitung). Positions in this
   * scope carry no {@code org_unit_id} ({@code org_unit_id IS NULL}).
   */
  AREA,

  /**
   * A single Staffel (Squadron). Positions in this scope reference an {@link OrgUnit} of kind
   * {@link OrgUnitKind#SQUADRON} and form the Staffelleiter / Kommandoleiter / Stv. / Ensign tree.
   */
  SQUADRON,

  /**
   * A single Spezialkommando (SK). Positions in this scope reference an {@link OrgUnit} of kind
   * {@link OrgUnitKind#SPECIAL_COMMAND} and only ever hold the 1-2 SK-Leiter (Commander).
   */
  SPECIAL_COMMAND,

  /**
   * A single Bereich (area/division) — epic #692, REQ-ORG-026. Positions in this scope reference an
   * {@link OrgUnit} of kind {@link OrgUnitKind#BEREICH} and form that Bereich's Bereichsleitung
   * sub-tree (Bereichsleiter / -koordinatoren / -operatoren). Unlike the legacy singleton {@link
   * #AREA}, this is per-Bereich: each Bereich carries its own leadership and the cardinality caps
   * (one Bereichsleiter) are scoped to the Bereich's {@code org_unit_id}.
   */
  BEREICH,

  /**
   * The Organisationsleitung (OL) — epic #692, REQ-ORG-026 — the single top tier above every
   * Bereich. Positions in this scope reference the {@link OrgUnit} of kind {@link
   * OrgUnitKind#ORGANISATIONSLEITUNG} and hold its members. Bound to the OL's {@code org_unit_id}
   * (not {@code NULL}), distinguishing it from the legacy {@link #AREA} scope.
   */
  OL
}
