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

import de.greluc.krt.profit.basetool.backend.notification.api.NotificationCauseCollector;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationCause;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Publishes one immutable notification cause after the domains have collected its events. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
class TransactionNotificationCauseCollector implements NotificationCauseCollector {

  private static final int AFTER_DOMAIN_COLLECTORS = 300;
  private static final String CAUSE_NAMESPACE = "basetool:notification:outcome:";
  private final ApplicationEventPublisher publisher;

  @Override
  public void prepare(@NotNull UUID causeId) {
    group(causeId);
  }

  @Override
  public void append(@NotNull UUID causeId, @NotNull List<NotificationEvent> events) {
    group(causeId).additional.addAll(events);
  }

  @Override
  public void appendPreferred(@NotNull UUID causeId, @NotNull NotificationEvent event) {
    group(causeId).preferred.add(event);
  }

  private Group group(UUID causeId) {
    return batch().groups.computeIfAbsent(causeId, _ -> new Group());
  }

  private Batch batch() {
    for (TransactionSynchronization synchronization :
        TransactionSynchronizationManager.getSynchronizations()) {
      if (synchronization instanceof Batch batch) {
        return batch;
      }
    }
    Batch batch = new Batch();
    TransactionSynchronizationManager.registerSynchronization(batch);
    return batch;
  }

  /** Collects the owning outcome and additional original events for a cause. */
  private static final class Group {
    private final List<NotificationEvent> preferred = new ArrayList<>();
    private final List<NotificationEvent> additional = new ArrayList<>();
  }

  /** Registers commit-bound delivery after the participating domains finish collection. */
  private final class Batch implements TransactionSynchronization {
    private final Map<UUID, Group> groups = new LinkedHashMap<>();

    /** Runs after before-commit domain collectors. */
    @Override
    public int getOrder() {
      return AFTER_DOMAIN_COLLECTORS;
    }

    /** Publishes nonempty groups through the existing after-commit listener. */
    @Override
    public void beforeCommit(boolean readOnly) {
      if (readOnly) {
        return;
      }
      groups.forEach((id, group) -> {
        if (!group.preferred.isEmpty() || !group.additional.isEmpty()) {
          UUID receiptId =
              UUID.nameUUIDFromBytes((CAUSE_NAMESPACE + id).getBytes(StandardCharsets.UTF_8));
          publisher.publishEvent(
              new NotificationCause(receiptId, group.preferred, group.additional));
        }
      });
    }
  }
}
