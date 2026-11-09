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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import static de.greluc.krt.profit.basetool.frontend.joborder.model.OrderListScope.ALL;
import static de.greluc.krt.profit.basetool.frontend.joborder.model.OrderListScope.ASSIGNED;
import static de.greluc.krt.profit.basetool.frontend.joborder.model.OrderListScope.MINE;
import static de.greluc.krt.profit.basetool.frontend.joborder.model.OrderListScope.TO_PROCESS;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link OrderListScope}: the offered segments and the resolved list. */
class OrderListScopeTest {

  @Test
  void aQueueMemberChoosesAmongFourSegmentsInDisplayOrder() {
    assertThat(OrderListScope.offered(true, true, false, true))
        .containsExactly(MINE, ASSIGNED, TO_PROCESS, ALL);
  }

  @Test
  void aQueueMemberWithoutOwnViewHasNoMineSegment() {
    assertThat(OrderListScope.offered(true, false, false, true))
        .containsExactly(ASSIGNED, TO_PROCESS, ALL);
  }

  @Test
  void anAssigneeWithoutQueueAccessCanChooseAssignedAndConcreteAll() {
    assertThat(OrderListScope.offered(false, false, true, false)).containsExactly(ASSIGNED, ALL);
  }

  @Test
  void aRequesterWhoIsAlsoAnAssigneeCanChooseConcreteAll() {
    assertThat(OrderListScope.offered(false, true, true, false))
        .containsExactly(MINE, ASSIGNED, ALL);
  }

  @Test
  void aRequesterCanChooseMineAndConcreteAllWithoutProcessingScope() {
    assertThat(OrderListScope.offered(false, true, false, false)).containsExactly(MINE, ALL);
    assertThat(OrderListScope.offered(false, false, false, false)).isEmpty();
  }

  @Test
  void aRequestedOfferedScopeIsHonoured() {
    assertThat(OrderListScope.resolve(ASSIGNED, true, true, false, true)).isEqualTo(ASSIGNED);
    assertThat(OrderListScope.resolve(TO_PROCESS, true, false, false, true)).isEqualTo(TO_PROCESS);
    assertThat(OrderListScope.resolve(MINE, false, true, true, false)).isEqualTo(MINE);
  }

  @Test
  void aScopeParameterNeverMovesACallerOntoAListTheyCannotChoose() {
    assertThat(OrderListScope.resolve(ALL, false, false, true, false)).isEqualTo(ALL);
    assertThat(OrderListScope.resolve(TO_PROCESS, false, false, true, false)).isEqualTo(ALL);
    assertThat(OrderListScope.resolve(MINE, false, false, true, false)).isEqualTo(ALL);
    assertThat(OrderListScope.resolve(ALL, false, true, false, false)).isEqualTo(ALL);
    assertThat(OrderListScope.resolve(ASSIGNED, false, true, false, false)).isEqualTo(ALL);
  }

  @Test
  void theDefaultListIsAllForEveryPermittedReader() {
    assertThat(OrderListScope.resolve(null, true, true, true, true)).isEqualTo(ALL);
    assertThat(OrderListScope.resolve(null, false, true, true, false)).isEqualTo(ALL);
    assertThat(OrderListScope.resolve(null, false, false, true, false)).isEqualTo(ALL);
  }

  @Test
  void currentUnitWorkIsAReadViewIndependentOfGlobalProfitAndWriteRoles() {
    assertThat(OrderListScope.offered(false, true, false, true))
        .containsExactly(MINE, TO_PROCESS, ALL);
    assertThat(OrderListScope.resolve(TO_PROCESS, false, true, false, true)).isEqualTo(TO_PROCESS);
    assertThat(OrderListScope.offered(true, true, false, false))
        .containsExactly(MINE, ASSIGNED, ALL);
  }

  @Test
  void parseOrNullKnowsExactlyTheFourNames() {
    assertThat(OrderListScope.parseOrNull("ASSIGNED")).isEqualTo(ASSIGNED);
    assertThat(OrderListScope.parseOrNull("ALL")).isEqualTo(ALL);
    assertThat(OrderListScope.parseOrNull("assigned")).isNull();
    assertThat(OrderListScope.parseOrNull(null)).isNull();
  }
}
