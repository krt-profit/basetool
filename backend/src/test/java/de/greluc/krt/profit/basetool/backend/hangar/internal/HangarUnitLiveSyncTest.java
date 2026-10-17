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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The unit hangar rooms hear a committed ship change once per unit (ADR-0260). */
@ExtendWith(MockitoExtension.class)
class HangarUnitLiveSyncTest {

  private static final UUID UNIT_A = UUID.fromString("1a2b3c4d-0000-4000-8000-00000000000a");
  private static final UUID UNIT_B = UUID.fromString("1a2b3c4d-0000-4000-8000-00000000000b");

  @Mock private LiveSyncPublisher relayService;

  @InjectMocks private HangarUnitLiveSync liveSync;

  @AfterEach
  void tearDown() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
    TransactionSynchronizationManager.unbindResourceIfPossible(liveSync);
  }

  @Test
  @DisplayName("after the commit each touched unit and the all-units room hear one frame")
  void publishesOncePerUnitAfterTheCommit() {
    TransactionSynchronizationManager.initSynchronization();

    liveSync.shipsChanged(Arrays.asList(unit(UNIT_A), null));
    liveSync.unitsChanged(List.of(UNIT_A, UNIT_B));
    verifyNoInteractions(relayService);

    complete(TransactionSynchronization.STATUS_COMMITTED);

    assertThat(publishedTopics())
        .containsExactly("hangar-unit:" + UNIT_A, "hangar-unit:" + UNIT_B, "hangar-units");
    assertThat(TransactionSynchronizationManager.getResource(liveSync)).isNull();
  }

  @Test
  @DisplayName("a rolled-back write announces nothing")
  void rollbackAnnouncesNothing() {
    TransactionSynchronizationManager.initSynchronization();

    liveSync.unitsChanged(List.of(UNIT_A));
    complete(TransactionSynchronization.STATUS_ROLLED_BACK);

    verifyNoInteractions(relayService);
    assertThat(TransactionSynchronizationManager.getResource(liveSync)).isNull();
  }

  @Test
  @DisplayName("a ship without a unit pokes only the all-units room")
  void unitlessShipPokesOnlyTheAllUnitsRoom() {
    liveSync.shipsChanged(Arrays.asList((OrgUnit) null));

    assertThat(publishedTopics()).containsExactly("hangar-units");
  }

  /**
   * Runs the registered synchronizations as a transaction manager would at the end.
   *
   * @param status the completion status
   */
  private static void complete(int status) {
    List<TransactionSynchronization> synchronizations =
        TransactionSynchronizationManager.getSynchronizations();
    if (status == TransactionSynchronization.STATUS_COMMITTED) {
      synchronizations.forEach(TransactionSynchronization::afterCommit);
    }
    synchronizations.forEach(s -> s.afterCompletion(status));
    TransactionSynchronizationManager.clearSynchronization();
  }

  /**
   * Reads the canonical topics the relay was handed, in order.
   *
   * @return the topics
   */
  private List<String> publishedTopics() {
    ArgumentCaptor<LiveSyncTopic> topics = ArgumentCaptor.forClass(LiveSyncTopic.class);
    verify(relayService, atLeastOnce()).publish(topics.capture(), eq(List.of("ships")));
    return topics.getAllValues().stream().map(LiveSyncTopic::canonical).toList();
  }

  /**
   * Builds an org unit with the given id.
   *
   * @param id the id
   * @return the unit
   */
  private static OrgUnit unit(UUID id) {
    SpecialCommand unit = new SpecialCommand();
    unit.setId(id);
    return unit;
  }
}
