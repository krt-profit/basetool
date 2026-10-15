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

package de.greluc.krt.profit.basetool.backend.orgunit.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Pins the "Logistician of a unit" rule of {@link OrgUnitMembership} (REQ-XCH-043, ADR-0257). */
class OrgUnitMembershipTest {

  /**
   * Every {@link MembershipRole} with and without {@code is_logistician}.
   *
   * @return one argument pair per rank and flag value
   */
  static @NotNull Stream<Arguments> everyRankWithAndWithoutFlag() {
    return Arrays.stream(MembershipRole.values())
        .flatMap(role -> Stream.of(Arguments.of(role, false), Arguments.of(role, true)));
  }

  /**
   * The expected classification of a rank without the flag; exhaustive, so a new rank fails to
   * compile until it is classified here.
   *
   * @param role the rank to classify
   * @return whether the rank alone makes the member a Logistician of its own unit
   */
  private static boolean rankAloneMakesLogistician(@NotNull MembershipRole role) {
    return switch (role) {
      case MEMBER -> false;
      case STAFFELLEITER,
          KOMMANDOLEITER,
          STELLV_KOMMANDOLEITER,
          ENSIGN,
          BEREICHSLEITER,
          BEREICHSKOORDINATOR,
          BEREICHSOPERATOR,
          OL_MEMBER,
          SK_LEAD ->
          true;
    };
  }

  /**
   * The flag or any leadership rank, including the Bereich and OL ranks, makes the membership a
   * Logistician of its own unit.
   *
   * @param role the membership's rank
   * @param flag the membership's {@code is_logistician} value
   */
  @ParameterizedTest(name = "{0}, is_logistician={1}")
  @MethodSource("everyRankWithAndWithoutFlag")
  void classifiesEveryRankWithAndWithoutTheFlag(@NotNull MembershipRole role, boolean flag) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setRole(role);
    membership.setLogistician(flag);

    assertThat(membership.isLogisticianOfOwnUnit())
        .isEqualTo(flag || rankAloneMakesLogistician(role));
  }
}
