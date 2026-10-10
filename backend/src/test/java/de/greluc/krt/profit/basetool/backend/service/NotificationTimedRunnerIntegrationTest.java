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

import de.greluc.krt.profit.basetool.backend.notification.api.TimedNoticeProducer;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Two backend instances sharing one database: only the one that holds the advisory lock raises the
 * time-based notices (REQ-NOTIF-026).
 */
@SpringBootTest
class NotificationTimedRunnerIntegrationTest {

  @Autowired private NotificationRepository notificationRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DataSource dataSource;

  private static TimedNoticeProducer counting(AtomicInteger calls) {
    return new TimedNoticeProducer() {
      @Override
      public String kind() {
        return "integration";
      }

      @Override
      public int produce(Instant now) {
        calls.incrementAndGet();
        return 1;
      }
    };
  }

  @Test
  void anInstanceWithoutTheLockSkipsTheRunAndTheNextOneProducesAfterItIsReleased()
      throws Exception {
    AtomicInteger calls = new AtomicInteger();
    NotificationTimedRunner runner =
        new NotificationTimedRunner(
            List.of(counting(calls)),
            notificationRepository,
            new SimpleMeterRegistry(),
            transactionManager);

    try (Connection otherInstance = dataSource.getConnection()) {
      try (PreparedStatement take = otherInstance.prepareStatement("SELECT pg_advisory_lock(?)")) {
        take.setLong(1, NotificationTimedRunner.LOCK_KEY);
        take.execute();
      }
      try {
        assertThat(runner.runOnce(Instant.now())).isZero();
        assertThat(calls).hasValue(0);
      } finally {
        try (PreparedStatement release =
            otherInstance.prepareStatement("SELECT pg_advisory_unlock(?)")) {
          release.setLong(1, NotificationTimedRunner.LOCK_KEY);
          release.execute();
        }
      }
    }

    assertThat(runner.runOnce(Instant.now())).isEqualTo(1);
    assertThat(calls).hasValue(1);
  }
}
