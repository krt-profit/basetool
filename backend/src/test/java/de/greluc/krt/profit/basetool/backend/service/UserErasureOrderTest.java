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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.platform.api.UserErasureParticipant;
import de.greluc.krt.profit.basetool.backend.platform.api.UserErasureParticipant.Phase;
import de.greluc.krt.profit.basetool.backend.privacy.api.UserDeletionService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pins the statements-in-order contract of the account deletion (REQ-DATA-008): the participants
 * the running application registers, in the order {@link UserDeletionService#inRunOrder()} runs
 * them, and that no two of a phase share a position.
 *
 * <p>The order is the one the deletion has always issued its statements in: the Materialbörse
 * audits the offers before the Lager rows they stand on go, the purges follow, then the
 * reassignments, then the unlinks.
 */
@SpringBootTest
@Transactional
class UserErasureOrderTest {

  /** The participants of today's modules, earliest first, as {@code Phase:Class}. */
  private static final List<String> EXPECTED_ORDER =
      List.of(
          "DELETE:MaterialExchangeOfferErasure",
          "DELETE:InventoryUserErasure",
          "DELETE:HangarUserErasure",
          "DELETE:PersonalInventoryErasureService",
          "DELETE:PersonalBlueprintUserErasure",
          "DELETE:NotificationUserErasure",
          "DELETE:MemberEvaluationErasureService",
          "REASSIGN:MissionOwnerReassignment",
          "REASSIGN:RefineryOwnerReassignment",
          "UNLINK:MissionMemberUnlink",
          "UNLINK:JobOrderMemberUnlink");

  @Autowired private UserDeletionService userDeletionService;

  @Test
  @DisplayName("the registered participants run in the recorded order")
  void theRegisteredParticipantsRunInTheRecordedOrder() {
    assertThat(describe(userDeletionService.inRunOrder())).isEqualTo(EXPECTED_ORDER);
  }

  @Test
  @DisplayName("no two participants of a phase share a position")
  void noTwoParticipantsOfAPhaseShareAPosition() {
    Set<String> seen = new HashSet<>();
    for (UserErasureParticipant participant : userDeletionService.inRunOrder()) {
      assertThat(seen.add(participant.phase() + "@" + participant.position()))
          .as("%s at %s", participant.getClass().getSimpleName(), participant.position())
          .isTrue();
    }
  }

  @Test
  @DisplayName("the phases run in the order DELETE, REASSIGN, UNLINK")
  void thePhasesRunInTheDeclaredOrder() {
    assertThat(List.of(Phase.values())).containsExactly(Phase.DELETE, Phase.REASSIGN, Phase.UNLINK);
  }

  private static List<String> describe(List<UserErasureParticipant> participants) {
    List<String> described = new ArrayList<>();
    for (UserErasureParticipant participant : participants) {
      described.add(
          participant.phase() + ":" + AopUtils.getTargetClass(participant).getSimpleName());
    }
    return described;
  }
}
