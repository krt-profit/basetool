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

import de.greluc.krt.profit.basetool.backend.model.Notification;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies that JDBC batching is on: a 100-recipient notification fan-out costs a handful of
 * prepared statements rather than one per recipient.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NotificationFanOutBatchingTest {

  private static final int RECIPIENTS = 100;

  @Autowired private NotificationRepository notificationRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private EntityManager entityManager;
  @Autowired private EntityManagerFactory entityManagerFactory;

  @Test
  void hundredRecipientFanOut_isBatched() {
    List<Notification> fanOut = new ArrayList<>();
    for (int i = 0; i < RECIPIENTS; i++) {
      User recipient = new User();
      recipient.setId(UUID.randomUUID());
      recipient.setUsername("fanout-" + i + "-" + UUID.randomUUID());
      userRepository.save(recipient);
      fanOut.add(
          Notification.builder()
              .recipientUserId(recipient.getId())
              .type(NotificationType.values()[0])
              .params("{}")
              .entityType("TEST")
              .entityId(UUID.randomUUID())
              .read(false)
              .build());
    }
    entityManager.flush();
    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);
    stats.clear();

    notificationRepository.saveAll(fanOut);
    entityManager.flush();

    assertThat(stats.getEntityInsertCount()).isEqualTo(RECIPIENTS);
    assertThat(stats.getPrepareStatementCount())
        .as("100 inserts should reach the database as a few JDBC batches, not 100 statements")
        .isLessThanOrEqualTo(5);
  }
}
