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

import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationRoleAudience;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationRule;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationRuleRepository;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationRuleSelector;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
    Map<NotificationType, Map<UUID, NotificationRoleAudience>> audiences =
        resolveRoleAudiences(event);
    dropOutranked(audiences, event.outrankedNotificationTypes());
    return recipientIds(audiences);
  }

  @NotNull
  Map<NotificationType, Map<UUID, NotificationRoleAudience>> resolveRoleAudiences(
      @NotNull NotificationEvent event) {
    List<NotificationRule> rules =
        notificationRuleRepository.findEnabledByEventTypeWithSelectors(event.eventType());
    Map<NotificationType, Map<UUID, NotificationRoleAudience>> byType =
        new EnumMap<>(NotificationType.class);
    for (NotificationRule rule : rules) {
      Map<UUID, NotificationRoleAudience> recipients = new HashMap<>();
      for (NotificationRuleSelector selector : rule.getSelectors()) {
        if (event
            .eventType()
            .permitsSelector(
                selector.getKind(), selector.getRoleCode(), event.subjectOrgUnit() != null)) {
          roleAudience(selector, event)
              .forEach((id, audience) ->
                  recipients.merge(id, audience, NotificationRoleAudience::union));
        }
      }
      if (rule.isExcludeActor() && event.actorSub() != null) {
        recipients.remove(event.actorSub());
      }
      recipients.keySet().removeIf(recipient -> !event.admitsRecipient(recipient));
      if (!recipients.isEmpty()) {
        Map<UUID, NotificationRoleAudience> typed =
            byType.computeIfAbsent(rule.getNotificationType(), _ -> new HashMap<>());
        recipients.forEach(
            (id, audience) -> typed.merge(id, audience, NotificationRoleAudience::union));
      }
    }
    return byType;
  }

  static @NotNull Map<NotificationType, Set<UUID>> recipientIds(
      Map<NotificationType, Map<UUID, NotificationRoleAudience>> byType) {
    return byType.entrySet().stream()
        .collect(
            Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Set.copyOf(entry.getValue().keySet())));
  }

  private @NotNull Map<UUID, NotificationRoleAudience> roleAudience(
      NotificationRuleSelector selector, NotificationEvent event) {
    return switch (selector.getKind()) {
      case SPECIFIC_USER ->
          selector.getUserId() == null
              ? Map.of()
              : Map.of(selector.getUserId(), NotificationRoleAudience.DIRECT);
      case ROLE -> byStoredRole(selector.getRoleCode());
      case ORG_RELATIVE_ROLE ->
          audience(resolveOrgRelative(selector, event), NotificationRoleAudience.MEMBER);
      case ACCOUNT_GRANT ->
          audience(resolveAccountGrant(event), NotificationRoleAudience.BANK_STAFF);
      case ACCOUNT_RESPONSIBLE ->
          audience(resolveAccountResponsible(event), NotificationRoleAudience.MEMBER);
      case REQUEST_RESPONSIBLE ->
          audience(resolveRequestResponsible(event), NotificationRoleAudience.MEMBER);
      case EVENT_RECIPIENT ->
          audience(resolveEventRecipient(event), directedAudience(event.eventType()));
      case EVENT_RECIPIENTS ->
          audience(event.contextRecipientUserIds(), directedAudience(event.eventType()));
      case MISSION_PARTICIPANTS ->
          audience(resolveMissionParticipants(event), NotificationRoleAudience.DIRECT);
      case MISSION_LEADERSHIP ->
          audience(resolveMissionLeadership(event), NotificationRoleAudience.DIRECT);
      case EXCHANGE_CLIENT_HOLDERS ->
          audience(resolveExchangeClientHolders(event), NotificationRoleAudience.DIRECT);
      case SEAT_HOLDERS ->
          audience(
              recipientResolutionService.resolveSeatHolders(), NotificationRoleAudience.MEMBER);
      case REQUIRED_APPROVER -> requiredApproverAudience(event);
    };
  }

  private Map<UUID, NotificationRoleAudience> byStoredRole(String roleCode) {
    if (roleCode == null) {
      return Map.of();
    }
    NotificationRoleAudience audience =
        switch (roleCode) {
          case Roles.KRT_MEMBER -> NotificationRoleAudience.MEMBER;
          case Roles.BANK_EMPLOYEE -> NotificationRoleAudience.BANK_STAFF;
          default -> NotificationRoleAudience.DIRECT;
        };
    return audience(recipientResolutionService.resolveByRole(roleCode), audience);
  }

  private static NotificationRoleAudience directedAudience(NotificationEventType eventType) {
    return switch (eventType) {
      case BANK_ACCOUNT_RESPONSIBLE_ASSIGNED,
          JOB_ORDER_CLAIM_WITHDRAWN,
          JOB_ORDER_PARTICIPATION_CHANGED ->
          NotificationRoleAudience.MEMBER;
      case BANK_GRANT_CHANGED -> NotificationRoleAudience.BANK_STAFF;
      default -> NotificationRoleAudience.DIRECT;
    };
  }

  private Map<UUID, NotificationRoleAudience> requiredApproverAudience(NotificationEvent event) {
    UUID requestId = event.contextBankRequestId();
    return requestId == null
        ? Map.of()
        : recipientResolutionService.requiredApproverAudience(requestId);
  }

  private static Map<UUID, NotificationRoleAudience> audience(
      Set<UUID> recipients, NotificationRoleAudience audience) {
    return recipients.stream()
        .collect(Collectors.toUnmodifiableMap(Function.identity(), _ -> audience));
  }

  /**
   * Removes every member resolved for an outranking type from the types it outranks, and drops a
   * type left without recipients (REQ-NOTIF-033).
   *
   * @param byType the resolved recipients by notification type, changed in place
   * @param outranked the outranked types by outranking type
   */
  static void dropOutranked(
      @NotNull Map<NotificationType, Map<UUID, NotificationRoleAudience>> byType,
      @NotNull Map<NotificationType, Set<NotificationType>> outranked) {
    outranked.forEach((outranking, lower) -> {
      Map<UUID, NotificationRoleAudience> holders = byType.get(outranking);
      if (holders == null) {
        return;
      }
      for (NotificationType type : lower) {
        Map<UUID, NotificationRoleAudience> recipients = byType.get(type);
        if (recipients != null) {
          recipients.keySet().removeAll(holders.keySet());
          if (recipients.isEmpty()) {
            byType.remove(type);
          }
        }
      }
    });
  }

  @NotNull
  private Set<UUID> resolveRequestResponsible(@NotNull NotificationEvent event) {
    UUID accountId = event.contextAccountId();
    if (accountId == null) {
      log.debug(
          "Event {} carries no bank account; REQUEST_RESPONSIBLE selector resolves to nobody",
          event.eventType());
      return Set.of();
    }
    return recipientResolutionService.resolveRequestResponsibleHolders(accountId);
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
  private Set<UUID> resolveMissionParticipants(@NotNull NotificationEvent event) {
    UUID missionId = event.contextMissionId();
    if (missionId == null) {
      log.debug(
          "Event {} carries no mission; MISSION_PARTICIPANTS selector resolves to nobody",
          event.eventType());
      return Set.of();
    }
    return recipientResolutionService.resolveMissionParticipants(
        missionId, event.contextMissionOnlyNotCheckedIn());
  }

  @NotNull
  private Set<UUID> resolveMissionLeadership(@NotNull NotificationEvent event) {
    UUID missionId = event.contextMissionId();
    if (missionId == null) {
      log.debug(
          "Event {} carries no mission; MISSION_LEADERSHIP selector resolves to nobody",
          event.eventType());
      return Set.of();
    }
    return recipientResolutionService.resolveMissionLeadership(missionId);
  }

  @NotNull
  private Set<UUID> resolveExchangeClientHolders(@NotNull NotificationEvent event) {
    UUID clientId = event.contextExchangeClientId();
    if (clientId == null && !event.contextAllExchangeClients()) {
      log.debug(
          "Event {} carries no exchange client; EXCHANGE_CLIENT_HOLDERS selector resolves to"
              + " nobody",
          event.eventType());
      return Set.of();
    }
    return recipientResolutionService.resolveExchangeClientHolders(clientId);
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
