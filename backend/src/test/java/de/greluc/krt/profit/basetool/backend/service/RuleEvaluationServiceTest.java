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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.event.BankBookingRequestConfirmedEvent;
import de.greluc.krt.profit.basetool.backend.event.BankBookingRequestCreatedEvent;
import de.greluc.krt.profit.basetool.backend.event.JobOrderCreatedEvent;
import de.greluc.krt.profit.basetool.backend.event.OrgUnitRef;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.model.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.NotificationRule;
import de.greluc.krt.profit.basetool.backend.model.NotificationRuleSelector;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.model.OrgRelativeRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.SelectorKind;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRuleRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RuleEvaluationServiceTest {

  private static final UUID RESPONSIBLE = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final UUID OFFICER_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID ADMIN_B = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
  private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

  @Mock private NotificationRuleRepository notificationRuleRepository;
  @Mock private RecipientResolutionService recipientResolutionService;
  @InjectMocks private RuleEvaluationService service;

  private static JobOrderCreatedEvent event(UUID actorSub) {
    return new JobOrderCreatedEvent(
        UUID.randomUUID(),
        7,
        "Build me a ship",
        new OrgUnitRef(RESPONSIBLE, OrgUnitKind.SQUADRON),
        "IRI",
        new OrgUnitRef(UUID.randomUUID(), OrgUnitKind.SQUADRON),
        "MATERIAL",
        actorSub);
  }

  private static NotificationRule ruleWithOfficerAndAdmin(boolean excludeActor) {
    NotificationRule rule =
        NotificationRule.builder()
            .eventType(NotificationEventType.JOB_ORDER_CREATED)
            .notificationType(NotificationType.JOB_ORDER_CREATED)
            .enabled(true)
            .excludeActor(excludeActor)
            .build();
    rule.addSelector(
        NotificationRuleSelector.builder()
            .kind(SelectorKind.ORG_RELATIVE_ROLE)
            .orgRelativeRole(OrgRelativeRole.OFFICER)
            .contextRole(NotificationContextRole.RESPONSIBLE)
            .build());
    rule.addSelector(
        NotificationRuleSelector.builder().kind(SelectorKind.ROLE).roleCode("ADMIN").build());
    return rule;
  }

  private static NotificationRule jobOrderRule(
      NotificationType notificationType,
      boolean excludeActor,
      NotificationRuleSelector... selectors) {
    NotificationRule rule =
        NotificationRule.builder()
            .eventType(NotificationEventType.JOB_ORDER_CREATED)
            .notificationType(notificationType)
            .enabled(true)
            .excludeActor(excludeActor)
            .build();
    for (NotificationRuleSelector selector : selectors) {
      rule.addSelector(selector);
    }
    return rule;
  }

  @Test
  void unionsSelectorsAndExcludesActor() {
    when(notificationRuleRepository.findEnabledByEventTypeWithSelectors(
            NotificationEventType.JOB_ORDER_CREATED))
        .thenReturn(List.of(ruleWithOfficerAndAdmin(true)));
    when(recipientResolutionService.resolveOrgRelative(OrgRelativeRole.OFFICER, RESPONSIBLE))
        .thenReturn(Set.of(OFFICER_A, ACTOR));
    when(recipientResolutionService.resolveByRole("ADMIN")).thenReturn(Set.of(ADMIN_B));

    Map<NotificationType, Set<UUID>> result = service.resolveRecipients(event(ACTOR));

    assertThat(result).containsOnlyKeys(NotificationType.JOB_ORDER_CREATED);
    assertThat(result.get(NotificationType.JOB_ORDER_CREATED))
        .containsExactlyInAnyOrder(OFFICER_A, ADMIN_B);
  }

  @Test
  void keepsActorWhenExcludeActorIsFalse() {
    when(notificationRuleRepository.findEnabledByEventTypeWithSelectors(
            NotificationEventType.JOB_ORDER_CREATED))
        .thenReturn(List.of(ruleWithOfficerAndAdmin(false)));
    when(recipientResolutionService.resolveOrgRelative(OrgRelativeRole.OFFICER, RESPONSIBLE))
        .thenReturn(Set.of(OFFICER_A, ACTOR));
    when(recipientResolutionService.resolveByRole("ADMIN")).thenReturn(Set.of(ADMIN_B));

    Map<NotificationType, Set<UUID>> result = service.resolveRecipients(event(ACTOR));

    assertThat(result.get(NotificationType.JOB_ORDER_CREATED))
        .containsExactlyInAnyOrder(OFFICER_A, ADMIN_B, ACTOR);
  }

  @Test
  void returnsEmptyWhenNoRuleMatches() {
    when(notificationRuleRepository.findEnabledByEventTypeWithSelectors(
            NotificationEventType.JOB_ORDER_CREATED))
        .thenReturn(List.of());

    assertThat(service.resolveRecipients(event(ACTOR))).isEmpty();
  }

  @Test
  void specificUserSelectorResolvesToThatUser() {
    NotificationRule rule =
        NotificationRule.builder()
            .eventType(NotificationEventType.JOB_ORDER_CREATED)
            .notificationType(NotificationType.JOB_ORDER_CREATED)
            .enabled(true)
            .excludeActor(true)
            .build();
    rule.addSelector(
        NotificationRuleSelector.builder()
            .kind(SelectorKind.SPECIFIC_USER)
            .userId(OFFICER_A)
            .build());
    when(notificationRuleRepository.findEnabledByEventTypeWithSelectors(
            NotificationEventType.JOB_ORDER_CREATED))
        .thenReturn(List.of(rule));

    Map<NotificationType, Set<UUID>> result = service.resolveRecipients(event(ACTOR));

    assertThat(result.get(NotificationType.JOB_ORDER_CREATED)).containsExactly(OFFICER_A);
  }

  @Test
  void accountGrantSelectorResolvesGrantHoldersOfTheEventsAccount() {
    // Given the UC2 rule: bank management (ROLE) + employees granted on the account (ACCOUNT_GRANT)
    UUID accountId = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    UUID grantedEmployee = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    UUID manager = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    NotificationRule rule =
        NotificationRule.builder()
            .eventType(NotificationEventType.BANK_BOOKING_REQUEST_CREATED)
            .notificationType(NotificationType.BANK_BOOKING_REQUEST_CREATED)
            .enabled(true)
            .excludeActor(true)
            .build();
    rule.addSelector(
        NotificationRuleSelector.builder()
            .kind(SelectorKind.ROLE)
            .roleCode("BANK_MANAGEMENT")
            .build());
    rule.addSelector(NotificationRuleSelector.builder().kind(SelectorKind.ACCOUNT_GRANT).build());
    BankBookingRequestCreatedEvent bankEvent =
        new BankBookingRequestCreatedEvent(
            UUID.randomUUID(),
            accountId,
            BankBookingRequestType.DEPOSIT,
            new BigDecimal("500"),
            "KB-0001",
            "greluc",
            "IRI",
            ACTOR);
    when(notificationRuleRepository.findEnabledByEventTypeWithSelectors(
            NotificationEventType.BANK_BOOKING_REQUEST_CREATED))
        .thenReturn(List.of(rule));
    when(recipientResolutionService.resolveByRole("BANK_MANAGEMENT")).thenReturn(Set.of(manager));
    when(recipientResolutionService.resolveAccountGrantHolders(accountId))
        .thenReturn(Set.of(grantedEmployee, ACTOR));

    Map<NotificationType, Set<UUID>> result = service.resolveRecipients(bankEvent);

    // The requesting actor is excluded; manager + granted employee remain.
    assertThat(result.get(NotificationType.BANK_BOOKING_REQUEST_CREATED))
        .containsExactlyInAnyOrder(manager, grantedEmployee);
  }

  @Test
  void eventRecipientSelectorResolvesToTheEventsDirectedRecipient() {
    // The decision-notification rule: notify the requester carried by the event (EVENT_RECIPIENT).
    UUID requester = UUID.fromString("00000000-0000-0000-0000-0000000000a4");
    UUID decider = UUID.fromString("00000000-0000-0000-0000-0000000000b4");
    NotificationRule rule =
        NotificationRule.builder()
            .eventType(NotificationEventType.BANK_BOOKING_REQUEST_CONFIRMED)
            .notificationType(NotificationType.BANK_BOOKING_REQUEST_CONFIRMED)
            .enabled(true)
            .excludeActor(true)
            .build();
    rule.addSelector(NotificationRuleSelector.builder().kind(SelectorKind.EVENT_RECIPIENT).build());
    BankBookingRequestConfirmedEvent confirmedEvent =
        new BankBookingRequestConfirmedEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "KB-0001",
            new BigDecimal("500"),
            requester,
            decider);
    when(notificationRuleRepository.findEnabledByEventTypeWithSelectors(
            NotificationEventType.BANK_BOOKING_REQUEST_CONFIRMED))
        .thenReturn(List.of(rule));

    Map<NotificationType, Set<UUID>> result = service.resolveRecipients(confirmedEvent);

    // The directed recipient is the requester; the deciding actor is not among the recipients.
    assertThat(result.get(NotificationType.BANK_BOOKING_REQUEST_CONFIRMED))
        .containsExactly(requester);
  }

  @Test
  void accountResponsibleSelectorResolvesTheResponsibleHolderOfTheEventsAccount() {
    // REQ-BANK-026/-034: the ACCOUNT_RESPONSIBLE selector notifies the account's responsible
    // holder,
    // resolved (via the org-unit seam) from the account carried by the event. Here on a confirm
    // decision producing the account-centric RESPONSIBLE_CONFIRMED type; the deciding actor is
    // excluded.
    UUID accountId = UUID.fromString("00000000-0000-0000-0000-0000000000c6");
    UUID requester = UUID.fromString("00000000-0000-0000-0000-0000000000a6");
    UUID responsible = UUID.fromString("00000000-0000-0000-0000-0000000000d6");
    NotificationRule rule =
        NotificationRule.builder()
            .eventType(NotificationEventType.BANK_BOOKING_REQUEST_CONFIRMED)
            .notificationType(NotificationType.BANK_BOOKING_REQUEST_RESPONSIBLE_CONFIRMED)
            .enabled(true)
            .excludeActor(true)
            .build();
    rule.addSelector(
        NotificationRuleSelector.builder().kind(SelectorKind.ACCOUNT_RESPONSIBLE).build());
    BankBookingRequestConfirmedEvent confirmedEvent =
        new BankBookingRequestConfirmedEvent(
            UUID.randomUUID(), accountId, "KB-0001", new BigDecimal("500"), requester, ACTOR);
    when(notificationRuleRepository.findEnabledByEventTypeWithSelectors(
            NotificationEventType.BANK_BOOKING_REQUEST_CONFIRMED))
        .thenReturn(List.of(rule));
    when(recipientResolutionService.resolveAccountResponsibleHolders(accountId))
        .thenReturn(Set.of(responsible, ACTOR));

    Map<NotificationType, Set<UUID>> result = service.resolveRecipients(confirmedEvent);

    // The responsible holder is notified; the deciding actor (ACTOR) is excluded.
    assertThat(result.get(NotificationType.BANK_BOOKING_REQUEST_RESPONSIBLE_CONFIRMED))
        .containsExactly(responsible);
  }

  // --- Gap 1: null-context selector guards must each resolve to nobody, never NPE ---

  @Test
  void accountGrantSelectorWithNoAccountResolvesToNobody() {
    // The job-order event carries no bank account (contextAccountId() defaults to null); an
    // ACCOUNT_GRANT selector must resolve to nobody without ever consulting the grant resolver.
    NotificationRule rule =
        jobOrderRule(
            NotificationType.JOB_ORDER_CREATED,
            true,
            NotificationRuleSelector.builder().kind(SelectorKind.ACCOUNT_GRANT).build());
    when(notificationRuleRepository.findEnabledByEventTypeWithSelectors(
            NotificationEventType.JOB_ORDER_CREATED))
        .thenReturn(List.of(rule));

    Map<NotificationType, Set<UUID>> result = service.resolveRecipients(event(ACTOR));

    assertThat(result).isEmpty();
    verify(recipientResolutionService, never()).resolveAccountGrantHolders(any());
  }

  @Test
  void accountResponsibleSelectorWithNoAccountResolvesToNobody() {
    // Same missing-account guard for the ACCOUNT_RESPONSIBLE selector.
    NotificationRule rule =
        jobOrderRule(
            NotificationType.JOB_ORDER_CREATED,
            true,
            NotificationRuleSelector.builder().kind(SelectorKind.ACCOUNT_RESPONSIBLE).build());
    when(notificationRuleRepository.findEnabledByEventTypeWithSelectors(
            NotificationEventType.JOB_ORDER_CREATED))
        .thenReturn(List.of(rule));

    Map<NotificationType, Set<UUID>> result = service.resolveRecipients(event(ACTOR));

    assertThat(result).isEmpty();
    verify(recipientResolutionService, never()).resolveAccountResponsibleHolders(any());
  }

  @Test
  void eventRecipientWithNullRecipientResolvesToNobody() {
    // The job-order event carries no directed recipient (contextRecipientUserId() defaults to
    // null),
    // so an EVENT_RECIPIENT selector must resolve to nobody rather than adding null.
    NotificationRule rule =
        jobOrderRule(
            NotificationType.JOB_ORDER_CREATED,
            true,
            NotificationRuleSelector.builder().kind(SelectorKind.EVENT_RECIPIENT).build());
    when(notificationRuleRepository.findEnabledByEventTypeWithSelectors(
            NotificationEventType.JOB_ORDER_CREATED))
        .thenReturn(List.of(rule));

    assertThat(service.resolveRecipients(event(ACTOR))).isEmpty();
  }

  @Test
  void orgRelativeWithMissingContextOrgUnitResolvesToNobody() {
    // The event exposes only RESPONSIBLE (requesting org unit is null), but the selector resolves
    // against REQUESTING -> no matching org unit -> nobody, and the resolver is never consulted.
    JobOrderCreatedEvent noRequesting =
        new JobOrderCreatedEvent(
            UUID.randomUUID(),
            7,
            "Build me a ship",
            new OrgUnitRef(RESPONSIBLE, OrgUnitKind.SQUADRON),
            "IRI",
            null,
            "MATERIAL",
            ACTOR);
    NotificationRule rule =
        jobOrderRule(
            NotificationType.JOB_ORDER_CREATED,
            true,
            NotificationRuleSelector.builder()
                .kind(SelectorKind.ORG_RELATIVE_ROLE)
                .orgRelativeRole(OrgRelativeRole.OFFICER)
                .contextRole(NotificationContextRole.REQUESTING)
                .build());
    when(notificationRuleRepository.findEnabledByEventTypeWithSelectors(
            NotificationEventType.JOB_ORDER_CREATED))
        .thenReturn(List.of(rule));

    Map<NotificationType, Set<UUID>> result = service.resolveRecipients(noRequesting);

    assertThat(result).isEmpty();
    verify(recipientResolutionService, never()).resolveOrgRelative(any(), any());
  }

  @Test
  void orgRelativeWithNullOrgRelativeRoleResolvesToNobody() {
    // A malformed ORG_RELATIVE_ROLE selector (no org-relative role set) contributes nobody and
    // never reaches the resolver, guarding against an NPE on the missing role.
    NotificationRule rule =
        jobOrderRule(
            NotificationType.JOB_ORDER_CREATED,
            true,
            NotificationRuleSelector.builder()
                .kind(SelectorKind.ORG_RELATIVE_ROLE)
                .contextRole(NotificationContextRole.RESPONSIBLE)
                .build());
    when(notificationRuleRepository.findEnabledByEventTypeWithSelectors(
            NotificationEventType.JOB_ORDER_CREATED))
        .thenReturn(List.of(rule));

    Map<NotificationType, Set<UUID>> result = service.resolveRecipients(event(ACTOR));

    assertThat(result).isEmpty();
    verify(recipientResolutionService, never()).resolveOrgRelative(any(), any());
  }

  @Test
  void specificUserSelectorWithNullUserResolvesToNobody() {
    // A SPECIFIC_USER selector with no target sub must contribute nobody instead of adding null.
    NotificationRule rule =
        jobOrderRule(
            NotificationType.JOB_ORDER_CREATED,
            true,
            NotificationRuleSelector.builder().kind(SelectorKind.SPECIFIC_USER).build());
    when(notificationRuleRepository.findEnabledByEventTypeWithSelectors(
            NotificationEventType.JOB_ORDER_CREATED))
        .thenReturn(List.of(rule));

    assertThat(service.resolveRecipients(event(ACTOR))).isEmpty();
  }

  @Test
  void roleSelectorWithNullRoleCodeResolvesToNobody() {
    // A ROLE selector with no role code must contribute nobody without hitting the role resolver.
    NotificationRule rule =
        jobOrderRule(
            NotificationType.JOB_ORDER_CREATED,
            true,
            NotificationRuleSelector.builder().kind(SelectorKind.ROLE).build());
    when(notificationRuleRepository.findEnabledByEventTypeWithSelectors(
            NotificationEventType.JOB_ORDER_CREATED))
        .thenReturn(List.of(rule));

    Map<NotificationType, Set<UUID>> result = service.resolveRecipients(event(ACTOR));

    assertThat(result).isEmpty();
    verify(recipientResolutionService, never()).resolveByRole(any());
  }

  // --- Gap 2: recipient merge across multiple enabled rules ---

  @Test
  void unionsRecipientsAcrossTwoRulesOfSameType() {
    // Two enabled rules for the same event both target JOB_ORDER_CREATED with disjoint recipients;
    // the engine must union them under a single key (computeIfAbsent(...).addAll), not overwrite.
    NotificationRule ruleA =
        jobOrderRule(
            NotificationType.JOB_ORDER_CREATED,
            false,
            NotificationRuleSelector.builder()
                .kind(SelectorKind.SPECIFIC_USER)
                .userId(OFFICER_A)
                .build());
    NotificationRule ruleB =
        jobOrderRule(
            NotificationType.JOB_ORDER_CREATED,
            false,
            NotificationRuleSelector.builder()
                .kind(SelectorKind.SPECIFIC_USER)
                .userId(ADMIN_B)
                .build());
    when(notificationRuleRepository.findEnabledByEventTypeWithSelectors(
            NotificationEventType.JOB_ORDER_CREATED))
        .thenReturn(List.of(ruleA, ruleB));

    Map<NotificationType, Set<UUID>> result = service.resolveRecipients(event(ACTOR));

    assertThat(result).containsOnlyKeys(NotificationType.JOB_ORDER_CREATED);
    assertThat(result.get(NotificationType.JOB_ORDER_CREATED))
        .containsExactlyInAnyOrder(OFFICER_A, ADMIN_B);
  }

  @Test
  void twoRulesProducingDifferentTypesYieldTwoKeys() {
    // Two enabled rules producing distinct notification types must yield two independent map keys.
    NotificationRule createdRule =
        jobOrderRule(
            NotificationType.JOB_ORDER_CREATED,
            false,
            NotificationRuleSelector.builder()
                .kind(SelectorKind.SPECIFIC_USER)
                .userId(OFFICER_A)
                .build());
    NotificationRule updatedRule =
        jobOrderRule(
            NotificationType.JOB_ORDER_UPDATED_BY_REQUESTER,
            false,
            NotificationRuleSelector.builder()
                .kind(SelectorKind.SPECIFIC_USER)
                .userId(ADMIN_B)
                .build());
    when(notificationRuleRepository.findEnabledByEventTypeWithSelectors(
            NotificationEventType.JOB_ORDER_CREATED))
        .thenReturn(List.of(createdRule, updatedRule));

    Map<NotificationType, Set<UUID>> result = service.resolveRecipients(event(ACTOR));

    assertThat(result)
        .containsOnlyKeys(
            NotificationType.JOB_ORDER_CREATED, NotificationType.JOB_ORDER_UPDATED_BY_REQUESTER);
    assertThat(result.get(NotificationType.JOB_ORDER_CREATED)).containsExactly(OFFICER_A);
    assertThat(result.get(NotificationType.JOB_ORDER_UPDATED_BY_REQUESTER))
        .containsExactly(ADMIN_B);
  }

  // --- Gap 3: empty-recipient guard and null-actor exclusion ---

  @Test
  void matchingRuleResolvingToNobodyProducesNoKey() {
    // The rule matches and its single ORG_RELATIVE_ROLE selector is exercised, but the resolver
    // returns nobody; the !recipients.isEmpty() guard must keep the type out of the map entirely
    // (no key at all, not a key mapping to an empty set).
    NotificationRule rule =
        jobOrderRule(
            NotificationType.JOB_ORDER_CREATED,
            false,
            NotificationRuleSelector.builder()
                .kind(SelectorKind.ORG_RELATIVE_ROLE)
                .orgRelativeRole(OrgRelativeRole.OFFICER)
                .contextRole(NotificationContextRole.RESPONSIBLE)
                .build());
    when(notificationRuleRepository.findEnabledByEventTypeWithSelectors(
            NotificationEventType.JOB_ORDER_CREATED))
        .thenReturn(List.of(rule));
    when(recipientResolutionService.resolveOrgRelative(OrgRelativeRole.OFFICER, RESPONSIBLE))
        .thenReturn(Set.of());

    Map<NotificationType, Set<UUID>> result = service.resolveRecipients(event(ACTOR));

    assertThat(result).isEmpty();
  }

  @Test
  void excludeActorWithNullActorKeepsAllRecipients() {
    // Anonymous/guest actor: with excludeActor=true and a null actorSub the exclusion is skipped
    // (event.actorSub() != null guard), so every resolved recipient survives.
    when(notificationRuleRepository.findEnabledByEventTypeWithSelectors(
            NotificationEventType.JOB_ORDER_CREATED))
        .thenReturn(List.of(ruleWithOfficerAndAdmin(true)));
    when(recipientResolutionService.resolveOrgRelative(OrgRelativeRole.OFFICER, RESPONSIBLE))
        .thenReturn(Set.of(OFFICER_A));
    when(recipientResolutionService.resolveByRole("ADMIN")).thenReturn(Set.of(ADMIN_B));

    Map<NotificationType, Set<UUID>> result = service.resolveRecipients(event(null));

    assertThat(result.get(NotificationType.JOB_ORDER_CREATED))
        .containsExactlyInAnyOrder(OFFICER_A, ADMIN_B);
  }
}
