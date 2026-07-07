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

import de.greluc.krt.profit.basetool.backend.event.NotificationEvent;
import de.greluc.krt.profit.basetool.backend.model.Notification;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import de.greluc.krt.profit.basetool.backend.support.NotificationParamsCodec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the notification rows a fired {@link NotificationEvent} produces.
 *
 * <p>Runs in its own transaction off the request thread (the after-commit async listener calls it),
 * so it deliberately never touches the originating aggregate — it works purely from the event's
 * scalars, which keeps it clear of the optimistic-locking traps in {@code CLAUDE.md} (no second
 * {@code @Version} bump on the job order). One row is written per resolved recipient; an event that
 * resolves to nobody writes nothing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationCreationService {

  private final RuleEvaluationService ruleEvaluationService;
  private final NotificationRepository notificationRepository;
  private final NotificationParamsCodec notificationParamsCodec;

  /**
   * Resolves recipients for the event and writes one notification per recipient per produced type.
   *
   * <p>Deliberately does NOT push the real-time SSE signal here (#1152): the push fires from {@link
   * NotificationEventListener} <em>after</em> this transaction commits, so the client's
   * unread-count refetch reads the committed rows (not a pre-commit stale count) and no Hikari
   * connection is pinned across the blocking SSE fan-out. This method returns the recipients the
   * listener publishes to.
   *
   * @param event the fired event
   * @return the deduplicated recipient {@code sub}s that received a notification (empty when none)
   */
  @Transactional
  public Set<UUID> createFromEvent(@NotNull NotificationEvent event) {
    Map<NotificationType, Set<UUID>> recipientsByType =
        ruleEvaluationService.resolveRecipients(event);
    if (recipientsByType.isEmpty()) {
      log.debug(
          "Event {} for entity {} resolved no recipients", event.eventType(), event.entityId());
      return Set.of();
    }
    String paramsJson = notificationParamsCodec.serialize(event.renderParams());
    List<Notification> toCreate = new ArrayList<>();
    for (Map.Entry<NotificationType, Set<UUID>> entry : recipientsByType.entrySet()) {
      NotificationType type = entry.getKey();
      for (UUID recipientSub : entry.getValue()) {
        toCreate.add(
            Notification.builder()
                .recipientSub(recipientSub)
                .type(type)
                .params(paramsJson)
                .entityType(event.entityType())
                .entityId(event.entityId())
                .read(false)
                .build());
      }
    }
    notificationRepository.saveAll(toCreate);
    log.info(
        "Created {} notification(s) for event {} entity {}",
        toCreate.size(),
        event.eventType(),
        event.entityId());
    Set<UUID> recipientSubs = new HashSet<>();
    recipientsByType.values().forEach(recipientSubs::addAll);
    return recipientSubs;
  }
}
