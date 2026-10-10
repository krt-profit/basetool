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

import de.greluc.krt.profit.basetool.backend.model.Notification;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import de.greluc.krt.profit.basetool.backend.notification.internal.NotificationParamsCodec;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * Persists the notification rows a fired {@link NotificationEvent} produces, one per resolved
 * recipient.
 *
 * <p>Works only from the event's scalars and never touches the originating aggregate.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationCreationService {

  private final RuleEvaluationService ruleEvaluationService;
  private final NotificationRepository notificationRepository;
  private final NotificationParamsCodec notificationParamsCodec;

  /**
   * Deletes the notifications the event supersedes (REQ-NOTIF-018), then writes one notification
   * per recipient per produced type; an event naming {@link
   * NotificationEvent#reconcileRecipients()} is reconciled instead (REQ-NOTIF-023).
   *
   * <p>Pushes no SSE signal itself; the caller pushes after this transaction commits.
   *
   * @param event the fired event
   * @return the recipients whose inbox changed, grouped by signal; cleared-only recipients appear
   *     under {@link NotificationSignal#refreshOnly()}
   */
  @Transactional
  @NotNull
  public Map<NotificationSignal, Set<UUID>> createFromEvent(@NotNull NotificationEvent event) {
    if (!event.reconcileRecipients().isEmpty()) {
      return reconcile(event);
    }
    Map<NotificationSignal, Set<UUID>> bySignal = new LinkedHashMap<>();
    Set<UUID> cleared = removeSupersededNotifications(event);
    if (!cleared.isEmpty()) {
      bySignal.put(NotificationSignal.refreshOnly(), new HashSet<>(cleared));
    }

    Map<NotificationType, Set<UUID>> recipientsByType =
        ruleEvaluationService.resolveRecipients(event);
    if (recipientsByType.isEmpty()) {
      log.debug(
          "Event {} for entity {} resolved no recipients", event.eventType(), event.entityId());
      return bySignal;
    }
    String paramsJson = notificationParamsCodec.serialize(event.renderParams());
    List<Notification> toCreate = new ArrayList<>();
    for (Map.Entry<NotificationType, Set<UUID>> entry : recipientsByType.entrySet()) {
      NotificationType type = entry.getKey();
      for (UUID recipientUserId : entry.getValue()) {
        toCreate.add(
            Notification.builder()
                .recipientUserId(recipientUserId)
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
    for (Map.Entry<NotificationType, Set<UUID>> entry : recipientsByType.entrySet()) {
      NotificationSignal signal =
          new NotificationSignal(
              entry.getKey(), event.entityType(), event.entityId(), event.renderParams());
      bySignal.computeIfAbsent(signal, key -> new HashSet<>()).addAll(entry.getValue());
    }
    return bySignal;
  }

  /**
   * Re-decides, for the event's {@link NotificationEvent#reconcileRecipients() candidates} only,
   * who holds a notice about the event's entity (REQ-NOTIF-023): a candidate the rules resolve now
   * and who holds none of {@link NotificationEvent#resolvesNotificationTypes()} gets one, a
   * candidate the rules no longer resolve loses theirs, and everyone else is left alone.
   *
   * @param event the reconciling event
   * @return the candidates whose inbox changed, grouped by signal
   */
  @NotNull
  private Map<NotificationSignal, Set<UUID>> reconcile(@NotNull NotificationEvent event) {
    Map<NotificationSignal, Set<UUID>> bySignal = new LinkedHashMap<>();
    Set<UUID> candidates = event.reconcileRecipients();
    Set<NotificationType> noticeTypes = event.resolvesNotificationTypes();
    if (noticeTypes.isEmpty() || event.entityType() == null || event.entityId() == null) {
      log.warn("Event {} reconciles without notice types or entity; ignored", event.eventType());
      return bySignal;
    }
    Set<UUID> holders =
        new HashSet<>(
            notificationRepository.findRecipientUserIdsByTypeInAndEntity(
                noticeTypes, event.entityType(), event.entityId()));
    holders.retainAll(candidates);

    Map<NotificationType, Set<UUID>> resolved = ruleEvaluationService.resolveRecipients(event);
    Set<UUID> entitled = new HashSet<>();
    resolved.values().forEach(entitled::addAll);
    entitled.retainAll(candidates);

    Set<UUID> stale = new HashSet<>(holders);
    stale.removeAll(entitled);
    if (!stale.isEmpty()) {
      int deleted =
          notificationRepository.deleteByTypeInAndEntityForRecipients(
              noticeTypes, event.entityType(), event.entityId(), stale);
      log.info(
          "Reconcile of {} {} removed {} notification(s) of {} member(s) on event {}",
          event.entityType(),
          event.entityId(),
          deleted,
          stale.size(),
          event.eventType());
      bySignal.put(NotificationSignal.refreshOnly(), stale);
    }

    String paramsJson = notificationParamsCodec.serialize(event.renderParams());
    List<Notification> toCreate = new ArrayList<>();
    for (Map.Entry<NotificationType, Set<UUID>> entry : resolved.entrySet()) {
      Set<UUID> missing = new HashSet<>(entry.getValue());
      missing.retainAll(candidates);
      missing.removeAll(holders);
      if (missing.isEmpty()) {
        continue;
      }
      for (UUID recipientUserId : missing) {
        toCreate.add(
            Notification.builder()
                .recipientUserId(recipientUserId)
                .type(entry.getKey())
                .params(paramsJson)
                .entityType(event.entityType())
                .entityId(event.entityId())
                .read(false)
                .build());
      }
      bySignal
          .computeIfAbsent(
              new NotificationSignal(
                  entry.getKey(), event.entityType(), event.entityId(), event.renderParams()),
              key -> new HashSet<>())
          .addAll(missing);
    }
    if (!toCreate.isEmpty()) {
      notificationRepository.saveAll(toCreate);
      log.info(
          "Reconcile of {} {} created {} notification(s) on event {}",
          event.entityType(),
          event.entityId(),
          toCreate.size(),
          event.eventType());
    }
    return bySignal;
  }

  /**
   * Deletes the notifications the event marks obsolete for its entity: those of {@link
   * NotificationEvent#resolvesNotificationTypes()} for everyone (REQ-NOTIF-018) and those of {@link
   * NotificationEvent#resolvesNotificationTypesForRecipients()} for {@link
   * NotificationEvent#supersedeRecipients()} only (REQ-NOTIF-025).
   *
   * @param event the fired event
   * @return the recipient subs whose stale notifications were removed; empty when none
   */
  @NotNull
  private Set<UUID> removeSupersededNotifications(@NotNull NotificationEvent event) {
    if (event.entityType() == null || event.entityId() == null) {
      return Set.of();
    }
    Set<UUID> affected = new HashSet<>();
    affected.addAll(removeForEveryone(event));
    affected.addAll(removeForRecipients(event));
    return affected;
  }

  @NotNull
  private Set<UUID> removeForEveryone(@NotNull NotificationEvent event) {
    Set<NotificationType> supersededTypes = event.resolvesNotificationTypes();
    if (supersededTypes.isEmpty()) {
      return Set.of();
    }
    Set<UUID> affected =
        new HashSet<>(
            notificationRepository.findRecipientUserIdsByTypeInAndEntity(
                supersededTypes, event.entityType(), event.entityId()));
    if (affected.isEmpty()) {
      return Set.of();
    }
    int deleted =
        notificationRepository.deleteByTypeInAndEntity(
            supersededTypes, event.entityType(), event.entityId());
    log.info(
        "Removed {} superseded notification(s) of {} for {} {} on event {}",
        deleted,
        supersededTypes,
        event.entityType(),
        event.entityId(),
        event.eventType());
    return affected;
  }

  @NotNull
  private Set<UUID> removeForRecipients(@NotNull NotificationEvent event) {
    Set<NotificationType> supersededTypes = event.resolvesNotificationTypesForRecipients();
    Set<UUID> recipients = event.supersedeRecipients();
    if (supersededTypes.isEmpty() || recipients.isEmpty()) {
      return Set.of();
    }
    Set<UUID> affected =
        new HashSet<>(
            notificationRepository.findRecipientUserIdsByTypeInAndEntity(
                supersededTypes, event.entityType(), event.entityId()));
    affected.retainAll(recipients);
    if (affected.isEmpty()) {
      return Set.of();
    }
    int deleted =
        notificationRepository.deleteByTypeInAndEntityForRecipients(
            supersededTypes, event.entityType(), event.entityId(), affected);
    log.info(
        "Removed {} superseded notification(s) of {} for {} member(s) on {} {} on event {}",
        deleted,
        supersededTypes,
        affected.size(),
        event.entityType(),
        event.entityId(),
        event.eventType());
    return affected;
  }
}
