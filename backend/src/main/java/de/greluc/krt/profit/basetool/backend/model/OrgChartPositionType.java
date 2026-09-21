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
 * The fixed catalogue of functional ranks ("Funktionsränge") a user can hold in the Profit-Bereich
 * org chart. Each value carries the {@link OrgChartScope} it belongs to so {@code OrgChartService}
 * can reject a position whose type does not match the scope it is being placed in (e.g. a {@code
 * SQUADRON_LEAD} dropped into the area leadership, or an {@code AREA_COMMANDER} into a Staffel).
 *
 * <p>The enum names are persisted verbatim via {@link jakarta.persistence.EnumType#STRING} and are
 * referenced literally by the {@code chk_org_chart_scope} / {@code chk_org_chart_parent} CHECK
 * constraints and the partial unique indexes in Flyway migration {@code V136}. Renaming or
 * reordering a constant therefore requires a coordinated migration — keep this enum and the V136
 * literals in lockstep.
 *
 * <p>These ranks are purely descriptive: holding one grants <b>no</b> application permission.
 * Authorization stays with the global roles and the {@code org_unit_membership} flags.
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

  /**
   * Bereichsleiter — the single head of a Bereich (epic #692, REQ-ORG-018). At most one per Bereich
   * (the cardinality is scoped to the Bereich's {@code org_unit_id}, unlike the legacy singleton
   * {@link #AREA_LEAD}).
   */
  BEREICHSLEITER(OrgChartScope.BEREICH),

  /** Bereichskoordinator — a Bereich's coordinator. Any number per Bereich. */
  BEREICHSKOORDINATOR(OrgChartScope.BEREICH),

  /** Bereichsoperator — a Bereich's operator. Any number per Bereich. */
  BEREICHSOPERATOR(OrgChartScope.BEREICH),

  /**
   * A member of the Organisationsleitung (epic #692, REQ-ORG-018). Any number — the OL is a body of
   * several people, mirroring the single {@code is_ol_member} membership flag (no separate OL head
   * rank).
   */
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
