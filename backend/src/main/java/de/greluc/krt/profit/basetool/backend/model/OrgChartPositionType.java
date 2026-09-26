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
import org.jetbrains.annotations.NotNull;

/**
 * The fixed catalogue of functional ranks ("Funktionsränge") in the Profit-Bereich org chart, each
 * bound to the {@link OrgChartScope} it may be placed in.
 *
 * <p>Persisted by name and referenced literally by database CHECK constraints, so a rename needs a
 * migration. Holding a rank grants no application permission.
 */
@RequiredArgsConstructor
public enum OrgChartPositionType {

  /** Bereichsleiter — the single head of the Profit-Bereich. At most one across the whole chart. */
  AREA_LEAD(OrgChartScope.AREA),

  /** Bereichskoordinator — area-leadership coordinator. Any number may be assigned. */
  AREA_COORDINATOR(OrgChartScope.AREA),

  /** Bereichsoperator — area-leadership operator. Any number may be assigned. */
  AREA_OPERATOR(OrgChartScope.AREA),

  /** Commander on the area-leadership level. Any number may be assigned. */
  AREA_COMMANDER(OrgChartScope.AREA),

  /** Staffelleiter — head of a single Staffel. At most one per Staffel. */
  SQUADRON_LEAD(OrgChartScope.SQUADRON),

  /** Kommandoleiter — command lead within a Staffel. At most four per Staffel. */
  COMMAND_LEAD(OrgChartScope.SQUADRON),

  /**
   * Stv. Kommandoleiter — deputy of a {@link #COMMAND_LEAD}. At most one per Kommandoleiter; its
   * {@code parent_id} points at the Kommandoleiter it deputises for.
   */
  DEPUTY_COMMAND_LEAD(OrgChartScope.SQUADRON),

  /**
   * Ensign within a Staffel. At most four per Staffel; its {@code parent_id} points at either the
   * {@link #SQUADRON_LEAD} (reporting directly) or a {@link #COMMAND_LEAD} (reporting into a
   * command).
   */
  ENSIGN(OrgChartScope.SQUADRON),

  /** Commander acting as SK-Leiter — leads a Spezialkommando. One or two per SK. */
  SK_COMMANDER(OrgChartScope.SPECIAL_COMMAND),

  /** Bereichsleiter — the single head of a Bereich (REQ-ORG-026); at most one per Bereich. */
  BEREICHSLEITER(OrgChartScope.BEREICH),

  /** Bereichskoordinator — a Bereich's coordinator. Any number per Bereich. */
  BEREICHSKOORDINATOR(OrgChartScope.BEREICH),

  /** Bereichsoperator — a Bereich's operator. Any number per Bereich. */
  BEREICHSOPERATOR(OrgChartScope.BEREICH),

  /** A member of the Organisationsleitung (REQ-ORG-026); any number per OL. */
  OL_MEMBER(OrgChartScope.OL);

  private final OrgChartScope scope;

  /**
   * Returns the scope this functional rank belongs to. Drives the scope/type consistency check in
   * {@code OrgChartService} (area ranks must be placed in the area leadership with no OrgUnit;
   * squadron ranks in a {@link OrgUnitKind#SQUADRON}; SK ranks in a {@link
   * OrgUnitKind#SPECIAL_COMMAND}).
   *
   * @return the owning scope; never {@code null}.
   */
  @NotNull
  public OrgChartScope scope() {
    return scope;
  }
}
