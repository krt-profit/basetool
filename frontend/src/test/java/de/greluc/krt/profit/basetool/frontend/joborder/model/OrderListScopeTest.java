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
    assertThat(OrderListScope.offered(true, true, false))
        .containsExactly(MINE, ASSIGNED, TO_PROCESS, ALL);
  }

  @Test
  void aQueueMemberWithoutOwnViewHasNoMineSegment() {
    assertThat(OrderListScope.offered(true, false, false))
        .containsExactly(ASSIGNED, TO_PROCESS, ALL);
  }

  @Test
  void anAssigneeWithoutQueueAccessHasOnlyTheAssignedSegment() {
    assertThat(OrderListScope.offered(false, false, true)).containsExactly(ASSIGNED);
  }

  @Test
  void aRequesterWhoIsAlsoAnAssigneeChoosesBetweenMineAndAssigned() {
    assertThat(OrderListScope.offered(false, true, true)).containsExactly(MINE, ASSIGNED);
  }

  @Test
  void aRequesterOnlyHasNothingToChoose() {
    assertThat(OrderListScope.offered(false, true, false)).isEmpty();
    assertThat(OrderListScope.offered(false, false, false)).isEmpty();
  }

  @Test
  void aRequestedOfferedScopeIsHonoured() {
    assertThat(OrderListScope.resolve(ASSIGNED, true, true, false)).isEqualTo(ASSIGNED);
    assertThat(OrderListScope.resolve(TO_PROCESS, true, false, false)).isEqualTo(TO_PROCESS);
    assertThat(OrderListScope.resolve(MINE, false, true, true)).isEqualTo(MINE);
  }

  @Test
  void aScopeParameterNeverMovesACallerOntoAListTheyCannotChoose() {
    assertThat(OrderListScope.resolve(ALL, false, false, true)).isEqualTo(ASSIGNED);
    assertThat(OrderListScope.resolve(TO_PROCESS, false, false, true)).isEqualTo(ASSIGNED);
    assertThat(OrderListScope.resolve(MINE, false, false, true)).isEqualTo(ASSIGNED);
    assertThat(OrderListScope.resolve(ALL, false, true, false)).isEqualTo(MINE);
    assertThat(OrderListScope.resolve(ASSIGNED, false, true, false)).isEqualTo(MINE);
  }

  @Test
  void theDefaultListIsAllForQueueMembersThenMineThenAssigned() {
    assertThat(OrderListScope.resolve(null, true, true, true)).isEqualTo(ALL);
    assertThat(OrderListScope.resolve(null, false, true, true)).isEqualTo(MINE);
    assertThat(OrderListScope.resolve(null, false, false, true)).isEqualTo(ASSIGNED);
  }

  @Test
  void parseOrNullKnowsExactlyTheFourNames() {
    assertThat(OrderListScope.parseOrNull("ASSIGNED")).isEqualTo(ASSIGNED);
    assertThat(OrderListScope.parseOrNull("ALL")).isEqualTo(ALL);
    assertThat(OrderListScope.parseOrNull("assigned")).isNull();
    assertThat(OrderListScope.parseOrNull(null)).isNull();
  }
}
