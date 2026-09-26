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

package de.greluc.krt.profit.basetool.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.Notification;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.model.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration coverage for {@link NotificationRepository} against the real Postgres test container,
 * so the V155 schema validates against the entity and the atomic bulk mutations behave as written.
 * Random recipient ids isolate each test from the shared container; assertions use only the freshly
 * created rows.
 */
@SpringBootTest
@ActiveProfiles("test")
class NotificationRepositoryIntegrationTest {

  @Autowired private NotificationRepository repository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private UserRepository userRepository;

  @PersistenceContext private EntityManager entityManager;

  /** Ids of the {@code app_user} rows this class created, removed again after each test. */
  private final Set<UUID> seededRecipients = new HashSet<>();

  /**
   * Creates the {@code app_user} row the recipient id must reference, unless it already exists
   * (REQ-DATA-008).
   *
   * @param recipient the notification's recipient id
   */
  private void ensureRecipient(UUID recipient) {
    if (userRepository.existsById(recipient)) {
      return;
    }
    User user = new User();
    user.setId(recipient);
    user.setUsername("recipient-" + recipient);
    userRepository.save(user);
    seededRecipients.add(recipient);
  }

  /**
   * Removes the committed users this class created, cascading to their notifications, so they do
   * not skew other test classes' totals.
   */
  @AfterEach
  void removeSeededRecipients() {
    transactionTemplate.executeWithoutResult(
        status -> {
          userRepository.deleteAllById(seededRecipients);
          seededRecipients.clear();
        });
  }

  private Notification save(UUID recipient, boolean read, Instant readAt) {
    return transactionTemplate.execute(
        status -> {
          ensureRecipient(recipient);
          Notification n =
              Notification.builder()
                  .recipientUserId(recipient)
                  .type(NotificationType.JOB_ORDER_CREATED)
                  .entityType("JOB_ORDER")
                  .entityId(UUID.randomUUID())
                  .read(read)
                  .readAt(readAt)
                  .build();
          return repository.save(n);
        });
  }

  private Notification save(
      UUID recipient, NotificationType type, String entityType, UUID entityId) {
    return transactionTemplate.execute(
        status -> {
          ensureRecipient(recipient);
          return repository.save(
              Notification.builder()
                  .recipientUserId(recipient)
                  .type(type)
                  .entityType(entityType)
                  .entityId(entityId)
                  .read(false)
                  .build());
        });
  }

  @Test
  void findByIdAndRecipientUserIdIsolatesByRecipient() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    Notification n = save(a, false, null);

    assertThat(repository.findByIdAndRecipientUserId(n.getId(), a)).isPresent();
    assertThat(repository.findByIdAndRecipientUserId(n.getId(), b)).isEmpty();
  }

  @Test
  void countsOnlyUnreadOfRecipient() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    save(a, false, null);
    save(a, false, null);
    save(a, true, Instant.now());
    save(b, false, null);

    assertThat(repository.countByRecipientUserIdAndReadFalse(a)).isEqualTo(2);
    assertThat(repository.countByRecipientUserIdAndReadFalse(b)).isEqualTo(1);
  }

  @Test
  void markAllReadForRecipientMarksOnlyThatRecipient() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    save(a, false, null);
    save(a, false, null);
    save(b, false, null);

    int updated =
        transactionTemplate.execute(status -> repository.markAllReadForRecipient(a, Instant.now()));

    assertThat(updated).isEqualTo(2);
    assertThat(repository.countByRecipientUserIdAndReadFalse(a)).isZero();
    assertThat(repository.countByRecipientUserIdAndReadFalse(b)).isEqualTo(1);
  }

  @Test
  void deleteAllReadForRecipientDeletesOnlyRead() {
    UUID a = UUID.randomUUID();
    save(a, true, Instant.now());
    Notification unread = save(a, false, null);

    int deleted = transactionTemplate.execute(status -> repository.deleteAllReadForRecipient(a));

    assertThat(deleted).isEqualTo(1);
    assertThat(
            repository.findAllByRecipientUserId(
                a, org.springframework.data.domain.Pageable.unpaged()))
        .extracting(Notification::getId)
        .containsExactly(unread.getId());
  }

  @Test
  void deleteReadOlderThanDeletesOnlyOldReadRows() {
    UUID a = UUID.randomUUID();
    Instant now = Instant.now();
    Notification old = save(a, true, now.minus(120, ChronoUnit.DAYS));
    Notification recent = save(a, true, now.minus(1, ChronoUnit.DAYS));
    Notification unread = save(a, false, null);

    int deleted =
        transactionTemplate.execute(
            status -> repository.deleteReadOlderThan(now.minus(90, ChronoUnit.DAYS)));

    assertThat(deleted).isEqualTo(1);
    assertThat(repository.findByIdAndRecipientUserId(old.getId(), a)).isEmpty();
    assertThat(repository.findByIdAndRecipientUserId(recent.getId(), a)).isPresent();
    assertThat(repository.findByIdAndRecipientUserId(unread.getId(), a)).isPresent();
  }

  /**
   * Backdates a row's {@code created_at} through native SQL, since Hibernate ignores the value set
   * on the entity.
   *
   * @param id the notification to age
   * @param createdAt the creation instant to write
   */
  private void backdateCreatedAt(UUID id, Instant createdAt) {
    transactionTemplate.executeWithoutResult(
        status ->
            entityManager
                .createNativeQuery("update notification set created_at = :ts where id = :id")
                .setParameter("ts", createdAt)
                .setParameter("id", id)
                .executeUpdate());
  }

  @Test
  void deleteUnreadOlderThanDeletesOnlyOldUnreadRows() {
    UUID a = UUID.randomUUID();
    Instant now = Instant.now();
    Notification oldUnread = save(a, false, null);
    Notification recentUnread = save(a, false, null);
    Notification oldRead = save(a, true, now.minus(1, ChronoUnit.DAYS));
    backdateCreatedAt(oldUnread.getId(), now.minus(200, ChronoUnit.DAYS));
    backdateCreatedAt(oldRead.getId(), now.minus(200, ChronoUnit.DAYS));

    int deleted =
        transactionTemplate.execute(
            status -> repository.deleteUnreadOlderThan(now.minus(180, ChronoUnit.DAYS)));

    assertThat(deleted).isEqualTo(1);
    assertThat(repository.findByIdAndRecipientUserId(oldUnread.getId(), a)).isEmpty();
    assertThat(repository.findByIdAndRecipientUserId(recentUnread.getId(), a)).isPresent();
    assertThat(repository.findByIdAndRecipientUserId(oldRead.getId(), a)).isPresent();
  }

  @Test
  void anUnreadBacklogNoLongerSurvivesIndefinitely() {
    UUID a = UUID.randomUUID();
    Instant now = Instant.now();
    Notification neverRead = save(a, false, null);
    backdateCreatedAt(neverRead.getId(), now.minus(3650, ChronoUnit.DAYS));

    transactionTemplate.execute(
        status -> repository.deleteUnreadOlderThan(now.minus(180, ChronoUnit.DAYS)));

    assertThat(repository.findByIdAndRecipientUserId(neverRead.getId(), a)).isEmpty();
  }

  @Test
  void supersedeByTypeAndEntityMatchesTypeAndEntityOnly() {
    UUID staffA = UUID.randomUUID();
    UUID staffB = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    UUID otherRequestId = UUID.randomUUID();
    Set<NotificationType> created = Set.of(NotificationType.BANK_BOOKING_REQUEST_CREATED);

    Notification a =
        save(
            staffA,
            NotificationType.BANK_BOOKING_REQUEST_CREATED,
            "BANK_BOOKING_REQUEST",
            requestId);
    Notification b =
        save(
            staffB,
            NotificationType.BANK_BOOKING_REQUEST_CREATED,
            "BANK_BOOKING_REQUEST",
            requestId);
    Notification decision =
        save(
            staffA,
            NotificationType.BANK_BOOKING_REQUEST_CONFIRMED,
            "BANK_BOOKING_REQUEST",
            requestId);
    Notification otherRequest =
        save(
            staffA,
            NotificationType.BANK_BOOKING_REQUEST_CREATED,
            "BANK_BOOKING_REQUEST",
            otherRequestId);

    assertThat(
            repository.findRecipientUserIdsByTypeInAndEntity(
                created, "BANK_BOOKING_REQUEST", requestId))
        .containsExactlyInAnyOrder(staffA, staffB);

    int deleted =
        transactionTemplate.execute(
            status ->
                repository.deleteByTypeInAndEntity(created, "BANK_BOOKING_REQUEST", requestId));

    assertThat(deleted).isEqualTo(2);
    assertThat(repository.findByIdAndRecipientUserId(a.getId(), staffA)).isEmpty();
    assertThat(repository.findByIdAndRecipientUserId(b.getId(), staffB)).isEmpty();
    assertThat(repository.findByIdAndRecipientUserId(decision.getId(), staffA)).isPresent();
    assertThat(repository.findByIdAndRecipientUserId(otherRequest.getId(), staffA)).isPresent();
  }
}
