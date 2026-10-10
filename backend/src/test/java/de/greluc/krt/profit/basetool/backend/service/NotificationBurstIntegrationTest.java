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

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.refinery.api.events.RefineryNotices;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Far more notification events than the notification executor's queue holds, published in one
 * transaction, still create every notice (REQ-NOTIF-029): the after-commit callbacks wait for room
 * instead of being dropped.
 */
@SpringBootTest
class NotificationBurstIntegrationTest {

  private static final UUID OWNER = UUID.fromString("44444444-4444-4444-4444-4444444490c1");
  private static final int BURST = 1_200;

  @Autowired private ApplicationEventPublisher eventPublisher;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MeterRegistry meterRegistry;

  @BeforeEach
  void seed() {
    cleanUp();
    User owner = new User();
    owner.setId(OWNER);
    owner.setUsername("burst-owner");
    owner.setApprovalStatus(ApprovalStatus.ACTIVE);
    owner.setInKeycloak(true);
    userRepository.saveAndFlush(owner);
  }

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("DELETE FROM notification WHERE recipient_user_id = ?", OWNER);
    jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", OWNER);
  }

  @Test
  void everyNoticeOfAnEventBurstInOneTransactionIsCreated() throws InterruptedException {
    double rejectedBefore =
        meterRegistry.counter(MetricNames.NOTIFICATION_EXECUTOR_REJECTED).count();

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ -> {
              for (int i = 0; i < BURST; i++) {
                eventPublisher.publishEvent(
                    RefineryNotices.ready(UUID.randomUUID(), OWNER, "Hub", "1 SCU Ore"));
              }
            });

    long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
    long created = notificationRepository.countByRecipientUserIdAndReadFalse(OWNER);
    while (created < BURST && System.nanoTime() < deadline) {
      Thread.sleep(200);
      created = notificationRepository.countByRecipientUserIdAndReadFalse(OWNER);
    }

    assertThat(created).isEqualTo(BURST);
    assertThat(meterRegistry.counter(MetricNames.NOTIFICATION_EXECUTOR_REJECTED).count())
        .isEqualTo(rejectedBefore);
  }
}
