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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import static de.greluc.krt.profit.basetool.backend.supply.internal.SupplyAccessPolicies.admin;
import static de.greluc.krt.profit.basetool.backend.supply.internal.SupplyAccessPolicies.manager;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Who manages a unit's needs and donations inside the active context, and that every manage flag
 * agrees with its write gate (REQ-ORG-032, REQ-SUP-003).
 */
class SupplyAccessPolicyTest {

  private static final UUID HEL = UUID.randomUUID();
  private static final UUID ARGON = UUID.randomUUID();

  /**
   * The callers, each with whether they manage Hel and Argon.
   *
   * @return the named callers with their expected answers
   */
  static Stream<Arguments> callers() {
    return Stream.of(
        Arguments.of(Named.of("unpinned admin", admin(null)), true, true),
        Arguments.of(Named.of("admin pinned to Hel", admin(HEL)), true, false),
        Arguments.of(Named.of("Logistician of Hel", manager(Set.of(HEL))), true, false),
        Arguments.of(Named.of("member without a grant", manager(Set.of())), false, false));
  }

  @ParameterizedTest
  @MethodSource("callers")
  void theManagedUnitsFollowThePinAndAgreeWithTheWriteGate(
      SupplyAccessPolicy policy, boolean managesHel, boolean managesArgon) {
    SupplyManagedUnits managed = policy.managedUnits();

    assertThat(managed.contains(HEL)).isEqualTo(managesHel);
    assertThat(managed.contains(ARGON)).isEqualTo(managesArgon);
    assertThat(policy.canManageUnit(HEL)).isEqualTo(managesHel);
    assertThat(policy.canManageUnit(ARGON)).isEqualTo(managesArgon);
  }

  @Test
  void onlyAnAdminWithoutAPinManagesEveryUnit() {
    assertThat(admin(null).managedUnits().everyUnit()).isTrue();
    assertThat(admin(HEL).managedUnits().everyUnit()).isFalse();
    assertThat(admin(HEL).managedUnits().unitIds()).containsExactly(HEL);
  }

  @Test
  void theStatisticsAnswerAPinnedAdminAndAManagerButNotAMemberWithoutAGrant() {
    assertThat(admin(HEL).canReadStats()).isTrue();
    assertThat(manager(Set.of(HEL)).canReadStats()).isTrue();
    assertThat(manager(Set.of()).canReadStats()).isFalse();
  }

  /**
   * The tab decisions do not depend on the pin (ADR-0329), while every manage flag, list and write
   * follows it (REQ-ORG-032).
   */
  @Test
  void aManagerPinnedToAnotherUnitKeepsTheTabRightsButManagesNothingThere() {
    SupplyAccessPolicy pinnedToArgon = manager(Set.of(HEL), Set.of());

    assertThat(pinnedToArgon.canReadStats()).isTrue();
    assertThat(pinnedToArgon.managedUnitsInAnyContext().contains(HEL)).isTrue();
    assertThat(pinnedToArgon.managedUnits().isEmpty()).isTrue();
  }

  @Test
  void anAdminManagesEveryUnitInAnyContextWhateverThePin() {
    assertThat(admin(HEL).managedUnitsInAnyContext().everyUnit()).isTrue();
    assertThat(admin(null).managedUnitsInAnyContext().everyUnit()).isTrue();
  }
}
