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

package de.greluc.krt.profit.basetool.backend.notification.internal;

import de.greluc.krt.profit.basetool.backend.notification.api.Notification;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationMetricNames;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationRepository;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationCause;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Persists original-event notices and grouped logical causes under their owning delivery policies.
 */
@Service
@RequiredArgsConstructor
public class NotificationCreationService {
  private final NotificationEventPreparation eventPreparation;
  private final NotificationRepository notificationRepository;
  private final MeterRegistry meterRegistry;

  /**
   * Applies an original event's cleanup, reconciliation and ordinary per-type delivery.
   *
   * @param event the original notification event
   * @return recipients whose Inbox changed, including cleanup-only changes
   */
  @Transactional
  public @NotNull Set<UUID> createFromEvent(@NotNull NotificationEvent event) {
    var prepared = eventPreparation.resolve(event);
    Set<UUID> changed = new HashSet<>(eventPreparation.removeObsolete(prepared));
    List<Notification> notices = eventPreparation.missing(prepared);
    persist(notices);
    recordCreated(counts(notices));
    notices.forEach(notice -> changed.add(notice.getRecipientUserId()));
    return changed;
  }

  /**
   * Delivers a logical revision once, selecting its first eligible original notice per recipient.
   *
   * @param cause immutable revision identity and original events in descending priority
   * @return recipients whose Inbox changed, or none for an already processed cause
   */
  @Transactional
  public @NotNull Set<UUID> createFromCause(@NotNull NotificationCause cause) {
    if (notificationRepository.claimCause(cause.id()) == 0) {
      return Set.of();
    }
    List<NotificationEventPreparation.Prepared> prepared =
        cause.events().stream().map(eventPreparation::resolve).toList();
    Set<UUID> changed = new HashSet<>();
    prepared.forEach(event -> changed.addAll(eventPreparation.removeObsolete(event)));
    Map<UUID, Notification> selected = new LinkedHashMap<>();
    for (var event : prepared) {
      for (Notification notice : eventPreparation.missing(event)) {
        selected.putIfAbsent(notice.getRecipientUserId(), notice);
      }
    }
    List<Notification> notices = List.copyOf(selected.values());
    persist(notices);
    recordAfterCommit(counts(notices));
    changed.addAll(selected.keySet());
    return Set.copyOf(changed);
  }

  private void persist(@NotNull List<Notification> notices) {
    if (!notices.isEmpty()) {
      notificationRepository.saveAll(notices);
    }
  }

  private static @NotNull Map<NotificationType, Integer> counts(
      @NotNull List<Notification> notices) {
    Map<NotificationType, Integer> byType = new LinkedHashMap<>();
    notices.forEach(notice -> byType.merge(notice.getType(), 1, Integer::sum));
    return Map.copyOf(byType);
  }

  private void recordCreated(@NotNull Map<NotificationType, Integer> byType) {
    byType.forEach((type, count) ->
        meterRegistry
            .counter(
                NotificationMetricNames.NOTIFICATION_CREATED,
                NotificationMetricNames.TAG_NOTIFICATION_TYPE,
                type.name())
            .increment(count));
  }

  private void recordAfterCommit(@NotNull Map<NotificationType, Integer> byType) {
    if (byType.isEmpty()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new CommittedNoticeCounts(byType));
  }

  /** Counts the stored notices only after their owning cause transaction commits. */
  @RequiredArgsConstructor
  private final class CommittedNoticeCounts implements TransactionSynchronization {
    private final Map<NotificationType, Integer> byType;

    /** Adds the committed per-type counts to the existing creation metric. */
    @Override
    public void afterCommit() {
      recordCreated(byType);
    }
  }
}
