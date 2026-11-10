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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncFanout;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real transaction suspension isolates inner notifications and transaction-local memos. */
@SpringBootTest
class TransactionResourceSuspensionIntegrationTest {

  @Autowired private PlatformTransactionManager transactionManager;

  @ParameterizedTest
  @CsvSource({"true,true", "true,false", "false,true", "false,false"})
  void requiresNewOwnsItsResourcesRegardlessOfEitherOutcome(
      boolean outerCommit, boolean innerCommit) {
    LiveSyncFanout fanout = mock(LiveSyncFanout.class);
    LiveSyncPublisher publisher = new LiveSyncPublisher(fanout, new SimpleMeterRegistry());
    LiveSyncTopic outerTopic = LiveSyncTopic.global(LiveSyncTopicClass.INVENTORY_ALL);
    LiveSyncTopic innerTopic = LiveSyncTopic.global(LiveSyncTopicClass.MATERIALBOARD);
    Object owner = new Object();
    TransactionTemplate outer = new TransactionTemplate(transactionManager);
    TransactionTemplate inner = new TransactionTemplate(transactionManager);
    inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    outer.executeWithoutResult(outerStatus -> {
      Object outerMemo = RefineryTransactionMemo.bound(owner, Object.class, Object::new);
      publisher.publishAfterCommit(outerTopic, List.of("stock"));
      inner.executeWithoutResult(innerStatus -> {
        assertThat(RefineryTransactionMemo.bound(owner, Object.class, Object::new))
            .isNotSameAs(outerMemo);
        publisher.publishAfterCommit(innerTopic, List.of("board"));
        if (!innerCommit) {
          innerStatus.setRollbackOnly();
        }
      });
      assertThat(RefineryTransactionMemo.bound(owner, Object.class, Object::new))
          .isSameAs(outerMemo);
      if (!outerCommit) {
        outerStatus.setRollbackOnly();
      }
    });
    verify(fanout, times(outerCommit ? 1 : 0)).publish(outerTopic, List.of("stock"));
    verify(fanout, times(innerCommit ? 1 : 0)).publish(innerTopic, List.of("board"));
    assertThat(TransactionSynchronizationManager.hasResource(owner)).isFalse();
    assertThat(TransactionSynchronizationManager.hasResource(publisher)).isFalse();
  }
}
