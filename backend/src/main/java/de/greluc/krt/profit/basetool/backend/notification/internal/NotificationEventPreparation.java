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
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationMuteService;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationParamsCodec;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationRepository;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Resolves original event policies and removes obsolete notices before new rows are built. */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(propagation = Propagation.MANDATORY)
public class NotificationEventPreparation {
  private final RuleEvaluationService ruleEvaluationService;
  private final NotificationRepository notificationRepository;
  private final NotificationParamsCodec notificationParamsCodec;
  private final NotificationMuteService notificationMuteService;

  @NotNull
  Prepared resolve(@NotNull NotificationEvent event) {
    if (reconciling(event) && !validReconciliation(event)) {
      log.warn("Event {} reconciles without notice types or entity; ignored", event.eventType());
      return new Prepared(event, Map.of());
    }
    return new Prepared(
        event,
        notificationMuteService.withoutMuted(ruleEvaluationService.resolveRecipients(event)));
  }

  @NotNull
  Set<UUID> removeObsolete(@NotNull Prepared prepared) {
    NotificationEvent event = prepared.event();
    if (reconciling(event)) {
      return validReconciliation(event) ? removeStaleReconciliation(prepared) : Set.of();
    }
    if (event.entityType() == null || event.entityId() == null) {
      return Set.of();
    }
    Set<UUID> changed = new HashSet<>(removeForEveryone(event));
    changed.addAll(removeForRecipients(event));
    return changed;
  }

  @NotNull
  List<Notification> missing(@NotNull Prepared prepared) {
    if (prepared.recipients().isEmpty()) {
      return List.of();
    }
    NotificationEvent event = prepared.event();
    Set<UUID> existing = reconciling(event) ? holders(event) : Set.of();
    List<Notification> result = new ArrayList<>();
    String params = notificationParamsCodec.serialize(event.renderParams());
    prepared.recipients().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> {
          Set<UUID> recipients = new HashSet<>(entry.getValue());
          if (reconciling(event)) {
            recipients.retainAll(event.reconcileRecipients());
            recipients.removeAll(existing);
          }
          for (UUID recipient : recipients) {
            result.add(
                Notification.builder()
                    .recipientUserId(recipient)
                    .type(entry.getKey())
                    .params(params)
                    .entityType(event.entityType())
                    .entityId(event.entityId())
                    .read(false)
                    .build());
          }
        });
    return result;
  }

  private @NotNull Set<UUID> removeStaleReconciliation(@NotNull Prepared prepared) {
    NotificationEvent event = prepared.event();
    Set<UUID> existing = holders(event);
    Set<UUID> entitled = new HashSet<>();
    prepared.recipients().values().forEach(entitled::addAll);
    entitled.retainAll(event.reconcileRecipients());
    existing.removeAll(entitled);
    if (existing.isEmpty()) {
      return Set.of();
    }
    notificationRepository.deleteByTypeInAndEntityForRecipients(
        event.resolvesNotificationTypes(), event.entityType(), event.entityId(), existing);
    return existing;
  }

  private @NotNull Set<UUID> holders(@NotNull NotificationEvent event) {
    Set<UUID> existing =
        new HashSet<>(
            notificationRepository.findRecipientUserIdsByTypeInAndEntity(
                event.resolvesNotificationTypes(), event.entityType(), event.entityId()));
    existing.retainAll(event.reconcileRecipients());
    return existing;
  }

  private @NotNull Set<UUID> removeForEveryone(@NotNull NotificationEvent event) {
    Set<NotificationType> types = event.resolvesNotificationTypes();
    if (types.isEmpty()) {
      return Set.of();
    }
    Set<UUID> changed =
        new HashSet<>(
            notificationRepository.findRecipientUserIdsByTypeInAndEntity(
                types, event.entityType(), event.entityId()));
    if (!changed.isEmpty()) {
      notificationRepository.deleteByTypeInAndEntity(types, event.entityType(), event.entityId());
    }
    return changed;
  }

  private @NotNull Set<UUID> removeForRecipients(@NotNull NotificationEvent event) {
    Set<NotificationType> types = event.resolvesNotificationTypesForRecipients();
    if (types.isEmpty() || event.supersedeRecipients().isEmpty()) {
      return Set.of();
    }
    Set<UUID> changed =
        new HashSet<>(
            notificationRepository.findRecipientUserIdsByTypeInAndEntity(
                types, event.entityType(), event.entityId()));
    changed.retainAll(event.supersedeRecipients());
    if (!changed.isEmpty()) {
      notificationRepository.deleteByTypeInAndEntityForRecipients(
          types, event.entityType(), event.entityId(), changed);
    }
    return changed;
  }

  private static boolean reconciling(@NotNull NotificationEvent event) {
    return !event.reconcileRecipients().isEmpty();
  }

  private static boolean validReconciliation(@NotNull NotificationEvent event) {
    return !event.resolvesNotificationTypes().isEmpty()
        && event.entityType() != null
        && event.entityId() != null;
  }

  /**
   * Original event and its resolved, originally muted delivery choices.
   *
   * @param event immutable original domain event
   * @param recipients eligible recipients by original notification type
   */
  record Prepared(
      @NotNull NotificationEvent event, @NotNull Map<NotificationType, Set<UUID>> recipients) {}
}
