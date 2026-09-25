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
 * The leadership rank a user holds on one {@link OrgUnitMembership} (REQ-ROLE-001); exactly one
 * rank per seat, restricted by a CHECK to ranks valid for the org unit's kind.
 *
 * <p>The rank is the source of truth for permissions; the org chart only mirrors it. The capability
 * flags {@link OrgUnitMembership#isLogistician()} / {@link OrgUnitMembership#isMissionManager()}
 * are independent of it.
 */
public enum MembershipRole {

  /**
   * No leadership rank — an ordinary member of the org unit (or a flag-less Bereich/OL seat). Valid
   * on any {@link OrgUnitKind}. Grants nothing beyond plain membership; the default for every row.
   */
  MEMBER,

  /**
   * Staffelleiter — overall lead of a single Staffel. Valid only on a {@code SQUADRON} membership;
   * at most one per squadron. Confers officer-equivalent reach over its own squadron
   * (REQ-ROLE-002).
   */
  STAFFELLEITER,

  /**
   * Kommandoleiter — leads one {@link KommandoGroup} within a Staffel. Valid only on a {@code
   * SQUADRON} membership and must reference a {@link OrgUnitMembership#getKommandoGroup() command
   * group}; up to four per squadron. Confers officer-equivalent reach over its own squadron.
   */
  KOMMANDOLEITER,

  /**
   * Stellvertretender Kommandoleiter — deputy of a {@link KommandoGroup}'s {@link #KOMMANDOLEITER}.
   * Valid only on a {@code SQUADRON} membership and must reference a command group; at most one per
   * group. Confers officer-equivalent reach over its own squadron.
   */
  STELLV_KOMMANDOLEITER,

  /**
   * Ensign — junior squadron-leadership rank below the deputy. Valid only on a {@code SQUADRON}
   * membership; may reference a {@link KommandoGroup} or be {@code null} ("allgemein der
   * Staffelleitung"); up to four per squadron. Confers officer-equivalent reach over its own
   * squadron.
   */
  ENSIGN,

  /**
   * Bereichsleiter — overall lead of a Bereich. Valid only on a {@code BEREICH} membership; at most
   * one per Bereich. Confers cascading, officer-equivalent reach over the Bereich and its children
   * (REQ-ORG-015). Is also organisationally part of the Organisationsleitung, but that membership
   * is organisational only and does NOT widen reach org-wide (REQ-ROLE-005).
   */
  BEREICHSLEITER,

  /**
   * Bereichskoordinator — coordinator within a Bereich. Valid only on a {@code BEREICH} membership.
   * Same cascading, officer-equivalent reach over the Bereich's children as {@link #BEREICHSLEITER}
   * (per-rank differentiation is deferred to later, per-feature work).
   */
  BEREICHSKOORDINATOR,

  /**
   * Bereichsoperator: operator within a Bereich, valid only on a {@code BEREICH} membership, with
   * the same cascading reach as {@link #BEREICHSLEITER}.
   */
  BEREICHSOPERATOR,

  /**
   * Member of the Organisationsleitung. Valid only on an {@code ORGANISATIONSLEITUNG} membership.
   * Confers cascading, officer-equivalent reach over <em>every</em> org unit (REQ-ORG-015) — never
   * admin rights.
   */
  OL_MEMBER,

  /**
   * SK-Leiter: lead of a Spezialkommando, valid only on a {@code SPECIAL_COMMAND} membership.
   * Confers logistician and mission-manager reach and member management over its own SK only.
   */
  SK_LEAD;

  /**
   * Whether this is one of the four in-squadron leadership ranks; these confer own-squadron reach
   * only and are exempt from the "a leader holds no Staffel" rule (REQ-ORG-017).
   *
   * @return {@code true} for {@link #STAFFELLEITER}, {@link #KOMMANDOLEITER}, {@link
   *     #STELLV_KOMMANDOLEITER} and {@link #ENSIGN}
   */
  public boolean isSquadronRank() {
    return this == STAFFELLEITER
        || this == KOMMANDOLEITER
        || this == STELLV_KOMMANDOLEITER
        || this == ENSIGN;
  }

  /**
   * Whether this is one of the three Bereich ranks (Bereichsleiter / -koordinator / -operator).
   *
   * @return {@code true} for {@link #BEREICHSLEITER}, {@link #BEREICHSKOORDINATOR} and {@link
   *     #BEREICHSOPERATOR}; {@code false} otherwise.
   */
  public boolean isAreaRank() {
    return this == BEREICHSLEITER || this == BEREICHSKOORDINATOR || this == BEREICHSOPERATOR;
  }

  /**
   * Whether this rank is an area rank or OL membership — the set used for the cartel-wide special-
   * account view and (from Phase 2) the {@code isAreaOrOlSeat} classifier. Excludes squadron ranks
   * and {@link #SK_LEAD}.
   *
   * @return {@code true} for the three area ranks and {@link #OL_MEMBER}; {@code false} otherwise.
   */
  public boolean isAreaOrOl() {
    return isAreaRank() || this == OL_MEMBER;
  }

  /**
   * Whether this rank makes the membership an oversight seat over its own org unit.
   *
   * @return {@code true} for every rank except {@link #MEMBER}
   */
  public boolean confersOwnLevelOversight() {
    return this != MEMBER;
  }

  /**
   * Whether this rank cascades reach downward to subordinate org units. Only area ranks (Bereich →
   * its Staffeln/SKs) and {@link #OL_MEMBER} (→ every org unit) cascade; squadron ranks and {@link
   * #SK_LEAD} stay own-unit only (REQ-ROLE-002, REQ-ORG-015/017).
   *
   * @return {@code true} for area ranks and {@link #OL_MEMBER}; {@code false} otherwise.
   */
  public boolean cascadesDownward() {
    return isAreaRank() || this == OL_MEMBER;
  }
}
