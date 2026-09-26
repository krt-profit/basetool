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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the authorisation readers based on {@link MembershipRole} behave exactly like the
 * former boolean leadership flags (REQ-ROLE-001/002).
 *
 * <ol>
 *   <li>For every legacy flag profile, the V184 backfilled rank yields the same result in each
 *       reader as the old flag did.
 *   <li>The squadron ranks grant own-squadron officer-equivalent reach only, with no downward
 *       cascade or area/OL reach.
 * </ol>
 */
class MembershipRoleMigrationEquivalenceTest {

  /**
   * The five legacy boolean leadership flags of {@code OrgUnitMembership}.
   *
   * @param lead the former {@code is_lead} flag.
   * @param bereichsleiter the former {@code is_bereichsleiter} flag.
   * @param bereichskoordinator the former {@code is_bereichskoordinator} flag.
   * @param bereichsoperator the former {@code is_bereichsoperator} flag.
   * @param olMember the former {@code is_ol_member} flag.
   */
  private record LegacyFlags(
      boolean lead,
      boolean bereichsleiter,
      boolean bereichskoordinator,
      boolean bereichsoperator,
      boolean olMember) {}

  /** Every possible legacy flag profile: each single flag set, plus none set. */
  private static final List<LegacyFlags> LEGACY_PROFILES =
      List.of(
          new LegacyFlags(false, false, false, false, false),
          new LegacyFlags(true, false, false, false, false),
          new LegacyFlags(false, true, false, false, false),
          new LegacyFlags(false, false, true, false, false),
          new LegacyFlags(false, false, false, true, false),
          new LegacyFlags(false, false, false, false, true));

  /** The four squadron leadership ranks. */
  private static final Set<MembershipRole> SQUADRON_RANKS =
      EnumSet.of(
          MembershipRole.STAFFELLEITER,
          MembershipRole.KOMMANDOLEITER,
          MembershipRole.STELLV_KOMMANDOLEITER,
          MembershipRole.ENSIGN);

  /**
   * Maps a legacy flag profile to its rank exactly as the {@code CASE} in {@code
   * V184__add_org_unit_membership_role_and_backfill.sql} does.
   *
   * @param f the legacy flag profile; never {@code null}.
   * @return the rank V184 assigns to a row carrying {@code f}.
   */
  private static MembershipRole backfill(LegacyFlags f) {
    if (f.lead()) {
      return MembershipRole.SK_LEAD;
    }
    if (f.bereichsleiter()) {
      return MembershipRole.BEREICHSLEITER;
    }
    if (f.bereichskoordinator()) {
      return MembershipRole.BEREICHSKOORDINATOR;
    }
    if (f.bereichsoperator()) {
      return MembershipRole.BEREICHSOPERATOR;
    }
    if (f.olMember()) {
      return MembershipRole.OL_MEMBER;
    }
    return MembershipRole.MEMBER;
  }

  /** {@code CustomJwtGrantedAuthoritiesConverter.confersFlatOfficerRole} before Phase 2. */
  private static boolean legacyConfersFlatOfficerRole(LegacyFlags f) {
    return f.lead()
        || f.bereichsleiter()
        || f.bereichskoordinator()
        || f.bereichsoperator()
        || f.olMember();
  }

  /** {@code CustomJwtGrantedAuthoritiesConverter} per-row own-unit officer mint before Phase 2. */
  private static boolean legacyOwnUnitOfficerReach(LegacyFlags f) {
    return f.lead();
  }

  /** {@code OrgUnitCascadeService} OL short-circuit before Phase 2. */
  private static boolean legacyOlReach(LegacyFlags f) {
    return f.olMember();
  }

  /** {@code OrgUnitCascadeService} per-row area cascade before Phase 2. */
  private static boolean legacyAreaCascade(LegacyFlags f) {
    return f.bereichsleiter() || f.bereichskoordinator() || f.bereichsoperator();
  }

  /** {@code OwnerScopeService.currentOversightScope} own-unit (SK-lead) branch before Phase 2. */
  private static boolean legacyOversightOwnUnit(LegacyFlags f) {
    return f.lead();
  }

  /** {@code OwnerScopeService.isOversightSeat} before Phase 2. */
  private static boolean legacyIsOversightSeat(LegacyFlags f) {
    return f.lead()
        || f.bereichsleiter()
        || f.bereichskoordinator()
        || f.bereichsoperator()
        || f.olMember();
  }

  /** {@code OwnerScopeService.isAreaOrOlSeat} before Phase 2. */
  private static boolean legacyIsAreaOrOlSeat(LegacyFlags f) {
    return f.bereichsleiter() || f.bereichskoordinator() || f.bereichsoperator() || f.olMember();
  }

  /** {@code OrgUnitMembershipService.userHoldsLeadershipRole} before Phase 2. */
  private static boolean legacyUserHoldsLeadershipRole(LegacyFlags f) {
    return f.lead()
        || f.bereichsleiter()
        || f.bereichskoordinator()
        || f.bereichsoperator()
        || f.olMember();
  }

  @Test
  void everyLegacyProfile_backfillsToRankThatReproducesEveryReaderPredicate() {
    for (LegacyFlags f : LEGACY_PROFILES) {
      MembershipRole role = backfill(f);

      assertEquals(
          legacyConfersFlatOfficerRole(f),
          role.confersOwnLevelOversight(),
          () -> "flat officer role diverged for " + role);

      assertEquals(
          legacyOwnUnitOfficerReach(f),
          role == MembershipRole.SK_LEAD || role.isSquadronRank(),
          () -> "own-unit officer reach diverged for " + role);

      assertEquals(
          legacyOlReach(f),
          role == MembershipRole.OL_MEMBER,
          () -> "OL reach diverged for " + role);

      assertEquals(
          legacyAreaCascade(f), role.isAreaRank(), () -> "area cascade diverged for " + role);

      assertEquals(
          legacyOversightOwnUnit(f),
          role == MembershipRole.SK_LEAD || role.isSquadronRank(),
          () -> "oversight own-unit diverged for " + role);

      assertEquals(
          legacyIsOversightSeat(f),
          role.confersOwnLevelOversight(),
          () -> "oversight seat diverged for " + role);

      assertEquals(
          legacyIsAreaOrOlSeat(f), role.isAreaOrOl(), () -> "area-or-OL seat diverged for " + role);

      assertEquals(
          legacyUserHoldsLeadershipRole(f),
          role == MembershipRole.SK_LEAD || role.isAreaOrOl(),
          () -> "silo-leader guard diverged for " + role);

      assertEquals(
          legacyAreaCascade(f) || legacyOlReach(f),
          role.cascadesDownward(),
          () -> "cascadesDownward diverged for " + role);
    }
  }

  @Test
  void confersOwnLevelOversight_isExactlyNonMember_forEveryRank() {
    for (MembershipRole role : MembershipRole.values()) {
      assertEquals(
          role != MembershipRole.MEMBER,
          role.confersOwnLevelOversight(),
          () -> "confersOwnLevelOversight must equal (role != MEMBER) for " + role);
    }
  }

  @Test
  void squadronRanks_getOwnSquadronGrantOnly_noCascadeNoAreaNoSiloGuard() {
    for (MembershipRole role : SQUADRON_RANKS) {
      assertTrue(
          role == MembershipRole.SK_LEAD || role.isSquadronRank(),
          () -> "squadron rank must mint own-unit officer reach: " + role);
      assertTrue(
          role.confersOwnLevelOversight(),
          () -> "squadron rank must be an oversight seat: " + role);

      assertFalse(role.isAreaRank(), () -> "squadron rank must not be an area rank: " + role);
      assertFalse(role.isAreaOrOl(), () -> "squadron rank must not be area-or-OL: " + role);
      assertFalse(role.cascadesDownward(), () -> "squadron rank must not cascade: " + role);

      assertFalse(
          role == MembershipRole.SK_LEAD || role.isAreaOrOl(),
          () -> "squadron rank must be exempt from the silo-leader guard: " + role);
    }
  }
}
