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

package de.greluc.krt.profit.basetool.backend.identity.api;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import de.greluc.krt.profit.basetool.backend.livesync.api.LivesyncMetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration tests for {@link RegistrationQueueListener} on the real persistence unit: the
 * listener is wired to {@link User}, so an edit of a pending account queues a live-sync frame for
 * the commit of the editing transaction, also when the edit is flushed only by the commit, and an
 * edit of an active account queues none.
 */
@SpringBootTest
class RegistrationQueueListenerIntegrationTest {

  @Autowired private TransactionTemplate transactions;
  @Autowired private UserRepository users;
  @Autowired private LiveSyncPublisher liveSyncPublisher;
  @Autowired private MeterRegistry meterRegistry;

  private final List<UUID> created = new ArrayList<>();

  @AfterEach
  void removeAccounts() {
    transactions.executeWithoutResult(_ -> users.deleteAllById(created));
  }

  @Test
  void editingAPendingAccount_queuesAFrameForTheCommit() {
    UUID id = account(ApprovalStatus.PENDING);

    assertThat(renameQueuesFrame(id)).isTrue();
  }

  @Test
  void editingAnActiveAccount_queuesNoFrame() {
    UUID id = account(ApprovalStatus.ACTIVE);

    assertThat(renameQueuesFrame(id)).isFalse();
  }

  @Test
  void anEditFlushedByTheCommit_isAnnouncedAfterTheCommit() {
    UUID id = account(ApprovalStatus.REJECTED);
    double before = adminDashboardFrames();

    transactions.executeWithoutResult(
        _ -> users.findById(id).orElseThrow().setDisplayName("Renamed " + id));

    assertThat(adminDashboardFrames()).isGreaterThan(before);
  }

  /**
   * Renames the account in a transaction of its own and reports whether its flush handed a frame to
   * the publisher, which binds itself to the transaction with the first frame it collects.
   *
   * @param id the account to rename
   * @return whether the transaction holds a frame for its commit
   */
  private boolean renameQueuesFrame(UUID id) {
    Boolean queued =
        transactions.execute(_ -> {
          User user = users.findById(id).orElseThrow();
          user.setDisplayName("Renamed " + id);
          users.flush();
          return TransactionSynchronizationManager.hasResource(liveSyncPublisher);
        });
    return Boolean.TRUE.equals(queued);
  }

  /**
   * Reads the count of frames the admin dashboard room accepted.
   *
   * @return the accepted-publish counter of the admin dashboard room
   */
  private double adminDashboardFrames() {
    return meterRegistry
        .counter(
            LivesyncMetricNames.LIVESYNC_PUBLISH_ACCEPTED,
            LivesyncMetricNames.TAG_TOPIC_CLASS,
            LiveSyncTopicClass.ADMIN_DASHBOARD.metricLabel())
        .count();
  }

  /**
   * Stores an account in the given approval status in a committed transaction of its own.
   *
   * @param status the account's approval status
   * @return the account's id
   */
  private UUID account(ApprovalStatus status) {
    UUID id = UUID.randomUUID();
    transactions.executeWithoutResult(_ -> {
      User user = new User();
      user.setId(id);
      user.setUsername("queue-" + id);
      user.setRank(1);
      user.setApprovalStatus(status);
      users.save(user);
    });
    created.add(id);
    return id;
  }
}
