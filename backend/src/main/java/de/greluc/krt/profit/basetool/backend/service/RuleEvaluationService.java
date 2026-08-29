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
import de.greluc.krt.profit.basetool.backend.event.OrgUnitRef;
import de.greluc.krt.profit.basetool.backend.model.NotificationRule;
import de.greluc.krt.profit.basetool.backend.model.NotificationRuleSelector;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRuleRepository;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * Turns a fired {@link NotificationEvent} into the set of recipients per produced notification type
 * by evaluating the enabled rules that match the event.
 *
 * <p>For each matching rule it unions the recipients resolved from every selector, drops the actor
 * when the rule sets {@code excludeActor}, and merges the result under the rule's notification
 * type. Selectors with missing/blank fields contribute nothing rather than failing the whole
 * evaluation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RuleEvaluationService {

  private final NotificationRuleRepository notificationRuleRepository;
  private final RecipientResolutionService recipientResolutionService;

  /**
   * Resolves the recipients an event should reach, grouped by the notification type to create.
   *
   * @param event the fired event
   * @return a map of notification type to the deduplicated recipient subs; never {@code null},
   *     empty when no enabled rule matches or no selector resolves to anyone
   */
  @NotNull
  public Map<NotificationType, Set<UUID>> resolveRecipients(@NotNull NotificationEvent event) {
    List<NotificationRule> rules =
        notificationRuleRepository.findEnabledByEventTypeWithSelectors(event.eventType());
    Map<NotificationType, Set<UUID>> byType = new EnumMap<>(NotificationType.class);
    for (NotificationRule rule : rules) {
      Set<UUID> recipients = new HashSet<>();
      for (NotificationRuleSelector selector : rule.getSelectors()) {
        recipients.addAll(resolveSelector(selector, event));
      }
      if (rule.isExcludeActor() && event.actorSub() != null) {
        recipients.remove(event.actorSub());
      }
      if (!recipients.isEmpty()) {
        byType
            .computeIfAbsent(rule.getNotificationType(), key -> new HashSet<>())
            .addAll(recipients);
      }
    }
    return byType;
  }

  @NotNull
  private Set<UUID> resolveSelector(
      @NotNull NotificationRuleSelector selector, @NotNull NotificationEvent event) {
    return switch (selector.getKind()) {
      case SPECIFIC_USER -> selector.getUserId() == null ? Set.of() : Set.of(selector.getUserId());
      case ROLE ->
          selector.getRoleCode() == null
              ? Set.of()
              : recipientResolutionService.resolveByRole(selector.getRoleCode());
      case ORG_RELATIVE_ROLE -> resolveOrgRelative(selector, event);
      case ACCOUNT_GRANT -> resolveAccountGrant(event);
      case ACCOUNT_RESPONSIBLE -> resolveAccountResponsible(event);
      case EVENT_RECIPIENT -> resolveEventRecipient(event);
    };
  }

  @NotNull
  private Set<UUID> resolveAccountGrant(@NotNull NotificationEvent event) {
    UUID accountId = event.contextAccountId();
    if (accountId == null) {
      log.debug(
          "Event {} carries no bank account; ACCOUNT_GRANT selector resolves to nobody",
          event.eventType());
      return Set.of();
    }
    return recipientResolutionService.resolveAccountGrantHolders(accountId);
  }

  @NotNull
  private Set<UUID> resolveAccountResponsible(@NotNull NotificationEvent event) {
    UUID accountId = event.contextAccountId();
    if (accountId == null) {
      log.debug(
          "Event {} carries no bank account; ACCOUNT_RESPONSIBLE selector resolves to nobody",
          event.eventType());
      return Set.of();
    }
    return recipientResolutionService.resolveAccountResponsibleHolders(accountId);
  }

  @NotNull
  private Set<UUID> resolveEventRecipient(@NotNull NotificationEvent event) {
    UUID recipientUserId = event.contextRecipientUserId();
    if (recipientUserId == null) {
      log.debug(
          "Event {} carries no directed recipient; EVENT_RECIPIENT selector resolves to nobody",
          event.eventType());
      return Set.of();
    }
    return Set.of(recipientUserId);
  }

  @NotNull
  private Set<UUID> resolveOrgRelative(
      @NotNull NotificationRuleSelector selector, @NotNull NotificationEvent event) {
    if (selector.getOrgRelativeRole() == null || selector.getContextRole() == null) {
      return Set.of();
    }
    OrgUnitRef ref = event.contextOrgUnits().get(selector.getContextRole());
    if (ref == null) {
      log.debug(
          "Event {} carries no org unit for context role {}; selector resolves to nobody",
          event.eventType(),
          selector.getContextRole());
      return Set.of();
    }
    return recipientResolutionService.resolveOrgRelative(selector.getOrgRelativeRole(), ref.id());
  }
}
