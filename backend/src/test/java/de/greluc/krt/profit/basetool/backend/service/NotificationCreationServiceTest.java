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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestCancelledEvent;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestConfirmedEvent;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestEvent;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestNoticesReconciledEvent;
import de.greluc.krt.profit.basetool.backend.joborder.api.events.JobOrderCreatedEvent;
import de.greluc.krt.profit.basetool.backend.model.Notification;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import de.greluc.krt.profit.basetool.backend.notification.internal.NotificationParamsCodec;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationCreationServiceTest {

  private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

  @Mock private RuleEvaluationService ruleEvaluationService;
  @Mock private NotificationRepository notificationRepository;
  @Mock private NotificationParamsCodec notificationParamsCodec;
  @Mock private NotificationMuteService notificationMuteService;
  @InjectMocks private NotificationCreationService service;

  @BeforeEach
  void muteNobody() {
    lenient()
        .when(notificationMuteService.withoutMuted(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  private static JobOrderCreatedEvent event() {
    return new JobOrderCreatedEvent(
        UUID.fromString("00000000-0000-0000-0000-00000000e001"),
        9,
        "h",
        new OrgUnitRef(UUID.randomUUID(), OrgUnitKind.SQUADRON),
        "IRI",
        new OrgUnitRef(UUID.randomUUID(), OrgUnitKind.SQUADRON),
        "MATERIAL",
        null);
  }

  @Test
  void createsOneRowPerRecipientWithEventFields() {
    JobOrderCreatedEvent event = event();
    when(ruleEvaluationService.resolveRecipients(event))
        .thenReturn(Map.of(NotificationType.JOB_ORDER_CREATED, Set.of(A, B)));
    when(notificationParamsCodec.serialize(any())).thenReturn("{\"displayId\":\"9\"}");

    Set<UUID> recipients = flatten(service.createFromEvent(event));

    assertThat(recipients).containsExactlyInAnyOrder(A, B);
    ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.captor();
    verify(notificationRepository).saveAll(captor.capture());
    List<Notification> saved = captor.getValue();
    assertThat(saved).hasSize(2);
    assertThat(saved)
        .allSatisfy(
            n -> {
              assertThat(n.getType()).isEqualTo(NotificationType.JOB_ORDER_CREATED);
              assertThat(n.getEntityType()).isEqualTo("JOB_ORDER");
              assertThat(n.getEntityId()).isEqualTo(event.entityId());
              assertThat(n.getParams()).isEqualTo("{\"displayId\":\"9\"}");
              assertThat(n.isRead()).isFalse();
            });
    assertThat(saved).extracting(Notification::getRecipientUserId).containsExactlyInAnyOrder(A, B);
  }

  @Test
  void writesNothingWhenNoRecipients() {
    JobOrderCreatedEvent event = event();
    when(ruleEvaluationService.resolveRecipients(event)).thenReturn(Map.of());

    Set<UUID> recipients = flatten(service.createFromEvent(event));

    assertThat(recipients).isEmpty();
    verify(notificationRepository, never()).saveAll(any());
  }

  @Test
  void plainEventDoesNotTouchSupersedeQueries() {
    JobOrderCreatedEvent event = event();
    when(ruleEvaluationService.resolveRecipients(event)).thenReturn(Map.of());

    service.createFromEvent(event);

    verify(notificationRepository, never())
        .findRecipientUserIdsByTypeInAndEntity(any(), any(), any());
    verify(notificationRepository, never()).deleteByTypeInAndEntity(any(), any(), any());
  }

  @Test
  void decisionEventRemovesSupersededCreatedNotificationsAndReturnsTheirRecipients() {
    UUID requestId = UUID.fromString("00000000-0000-0000-0000-00000000e777");
    UUID requester = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    UUID staffA = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    UUID staffB = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    BankBookingRequestConfirmedEvent event =
        new BankBookingRequestConfirmedEvent(
            requestId, UUID.randomUUID(), "KB-0001", new BigDecimal("500"), requester, staffA);
    Set<NotificationType> superseded = BankBookingRequestEvent.DECIDED_REQUEST_NOTICES;
    when(notificationRepository.findRecipientUserIdsByTypeInAndEntity(
            superseded, "BANK_BOOKING_REQUEST", requestId))
        .thenReturn(List.of(staffA, staffB));
    when(notificationRepository.deleteByTypeInAndEntity(
            superseded, "BANK_BOOKING_REQUEST", requestId))
        .thenReturn(2);
    when(ruleEvaluationService.resolveRecipients(event))
        .thenReturn(Map.of(NotificationType.BANK_BOOKING_REQUEST_CONFIRMED, Set.of(requester)));
    when(notificationParamsCodec.serialize(any())).thenReturn("{}");

    Set<UUID> affected = flatten(service.createFromEvent(event));

    assertThat(affected).containsExactlyInAnyOrder(staffA, staffB, requester);
    verify(notificationRepository)
        .deleteByTypeInAndEntity(superseded, "BANK_BOOKING_REQUEST", requestId);
    ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.captor();
    verify(notificationRepository).saveAll(captor.capture());
    assertThat(captor.getValue())
        .singleElement()
        .satisfies(
            n -> {
              assertThat(n.getType()).isEqualTo(NotificationType.BANK_BOOKING_REQUEST_CONFIRMED);
              assertThat(n.getRecipientUserId()).isEqualTo(requester);
            });
  }

  @Test
  void withdrawalEventOnlyRemovesAndCreatesNothing() {
    UUID requestId = UUID.fromString("00000000-0000-0000-0000-00000000e888");
    UUID staff = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    BankBookingRequestCancelledEvent event =
        new BankBookingRequestCancelledEvent(requestId, UUID.randomUUID(), UUID.randomUUID());
    Set<NotificationType> superseded = BankBookingRequestEvent.DECIDED_REQUEST_NOTICES;
    when(notificationRepository.findRecipientUserIdsByTypeInAndEntity(
            superseded, "BANK_BOOKING_REQUEST", requestId))
        .thenReturn(List.of(staff));
    when(notificationRepository.deleteByTypeInAndEntity(
            superseded, "BANK_BOOKING_REQUEST", requestId))
        .thenReturn(1);
    when(ruleEvaluationService.resolveRecipients(event)).thenReturn(Map.of());

    Set<UUID> affected = flatten(service.createFromEvent(event));

    assertThat(affected).containsExactly(staff);
    verify(notificationRepository, never()).saveAll(any());
  }

  @Test
  void supersedeIsSkippedWhenNoStaleNotificationsExist() {
    UUID requestId = UUID.fromString("00000000-0000-0000-0000-00000000e999");
    BankBookingRequestCancelledEvent event =
        new BankBookingRequestCancelledEvent(requestId, UUID.randomUUID(), UUID.randomUUID());
    when(notificationRepository.findRecipientUserIdsByTypeInAndEntity(
            eq(BankBookingRequestEvent.DECIDED_REQUEST_NOTICES),
            eq("BANK_BOOKING_REQUEST"),
            eq(requestId)))
        .thenReturn(List.of());
    when(ruleEvaluationService.resolveRecipients(event)).thenReturn(Map.of());

    Set<UUID> affected = flatten(service.createFromEvent(event));

    assertThat(affected).isEmpty();
    verify(notificationRepository, never()).deleteByTypeInAndEntity(any(), any(), any());
    verify(notificationRepository, never()).saveAll(any());
  }

  /**
   * A reconciling event for one request whose notices two staff members, the former holder and
   * nobody else already hold.
   *
   * @param changed the members who became or stopped being responsible
   * @return the event
   */
  private static BankBookingRequestNoticesReconciledEvent reconciled(Set<UUID> changed) {
    return new BankBookingRequestNoticesReconciledEvent(
        UUID.fromString("00000000-0000-0000-0000-00000000f001"),
        UUID.fromString("00000000-0000-0000-0000-00000000f002"),
        BankBookingRequestType.WITHDRAWAL,
        new BigDecimal("500"),
        "KB-0007",
        "requester",
        "IRI",
        UUID.fromString("00000000-0000-0000-0000-00000000f003"),
        changed);
  }

  @Test
  void reconcileMovesTheNoticeFromTheFormerToTheNewHolderOnly() {
    UUID formerHolder = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    UUID newHolder = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    UUID staff = UUID.fromString("00000000-0000-0000-0000-0000000000f3");
    BankBookingRequestNoticesReconciledEvent event = reconciled(Set.of(formerHolder, newHolder));
    Set<NotificationType> notices = BankBookingRequestEvent.OPEN_REQUEST_NOTICES;
    when(notificationRepository.findRecipientUserIdsByTypeInAndEntity(
            notices, "BANK_BOOKING_REQUEST", event.entityId()))
        .thenReturn(List.of(formerHolder, staff));
    when(ruleEvaluationService.resolveRecipients(event))
        .thenReturn(
            Map.of(NotificationType.BANK_BOOKING_REQUEST_CREATED, Set.of(staff, newHolder)));
    when(notificationRepository.deleteByTypeInAndEntityForRecipients(
            notices, "BANK_BOOKING_REQUEST", event.entityId(), Set.of(formerHolder)))
        .thenReturn(1);
    when(notificationParamsCodec.serialize(any())).thenReturn("{}");

    Set<UUID> affected = flatten(service.createFromEvent(event));

    assertThat(affected).containsExactlyInAnyOrder(formerHolder, newHolder);
    verify(notificationRepository, never()).deleteByTypeInAndEntity(any(), any(), any());
    ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.captor();
    verify(notificationRepository).saveAll(captor.capture());
    assertThat(captor.getValue())
        .singleElement()
        .satisfies(
            n -> {
              assertThat(n.getRecipientUserId()).isEqualTo(newHolder);
              assertThat(n.getType()).isEqualTo(NotificationType.BANK_BOOKING_REQUEST_CREATED);
              assertThat(n.getEntityId()).isEqualTo(event.entityId());
            });
  }

  @Test
  void reconcileKeepsTheNoticeOfAFormerHolderAnotherSelectorStillReaches() {
    UUID formerHolder = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    BankBookingRequestNoticesReconciledEvent event = reconciled(Set.of(formerHolder));
    Set<NotificationType> notices = BankBookingRequestEvent.OPEN_REQUEST_NOTICES;
    when(notificationRepository.findRecipientUserIdsByTypeInAndEntity(
            notices, "BANK_BOOKING_REQUEST", event.entityId()))
        .thenReturn(List.of(formerHolder));
    when(ruleEvaluationService.resolveRecipients(event))
        .thenReturn(Map.of(NotificationType.BANK_BOOKING_REQUEST_CREATED, Set.of(formerHolder)));

    Set<UUID> affected = flatten(service.createFromEvent(event));

    assertThat(affected).isEmpty();
    verify(notificationRepository, never())
        .deleteByTypeInAndEntityForRecipients(any(), any(), any(), any());
    verify(notificationRepository, never()).saveAll(any());
  }

  @Test
  void reconcileNeverNotifiesAnEntitledMemberWhoIsNotACandidate() {
    UUID newHolder = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    UUID staffWhoDeletedTheirNotice = UUID.fromString("00000000-0000-0000-0000-0000000000f4");
    BankBookingRequestNoticesReconciledEvent event = reconciled(Set.of(newHolder));
    when(notificationRepository.findRecipientUserIdsByTypeInAndEntity(
            BankBookingRequestEvent.OPEN_REQUEST_NOTICES, "BANK_BOOKING_REQUEST", event.entityId()))
        .thenReturn(List.of());
    when(ruleEvaluationService.resolveRecipients(event))
        .thenReturn(
            Map.of(
                NotificationType.BANK_BOOKING_REQUEST_CREATED,
                Set.of(newHolder, staffWhoDeletedTheirNotice)));
    when(notificationParamsCodec.serialize(any())).thenReturn("{}");

    Set<UUID> affected = flatten(service.createFromEvent(event));

    assertThat(affected).containsExactly(newHolder);
    ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.captor();
    verify(notificationRepository).saveAll(captor.capture());
    assertThat(captor.getValue())
        .extracting(Notification::getRecipientUserId)
        .containsExactly(newHolder);
  }

  @Test
  void perRecipientSupersedeClearsTheNoticeOfTheNamedMemberOnly() {
    UUID entity = UUID.fromString("00000000-0000-0000-0000-00000000d001");
    StubNotificationEvent event =
        StubNotificationEvent.builder()
            .resolvesNotificationTypesForRecipients(Set.of(NotificationType.JOB_ORDER_CREATED))
            .supersedeRecipients(Set.of(A))
            .build();
    when(notificationRepository.findRecipientUserIdsByTypeInAndEntity(
            Set.of(NotificationType.JOB_ORDER_CREATED), "STUB", entity))
        .thenReturn(List.of(A, B));
    when(ruleEvaluationService.resolveRecipients(event)).thenReturn(Map.of());

    Map<NotificationSignal, Set<UUID>> result = service.createFromEvent(event);

    verify(notificationRepository)
        .deleteByTypeInAndEntityForRecipients(
            Set.of(NotificationType.JOB_ORDER_CREATED), "STUB", entity, Set.of(A));
    verify(notificationRepository, never()).deleteByTypeInAndEntity(any(), any(), any());
    assertThat(result).containsOnlyKeys(NotificationSignal.refreshOnly());
    assertThat(flatten(result)).containsExactly(A);
  }

  @Test
  void perRecipientSupersedeDoesNothingWhenTheNamedMemberHoldsNoNotice() {
    UUID entity = UUID.fromString("00000000-0000-0000-0000-00000000d001");
    StubNotificationEvent event =
        StubNotificationEvent.builder()
            .resolvesNotificationTypesForRecipients(Set.of(NotificationType.JOB_ORDER_CREATED))
            .supersedeRecipients(Set.of(A))
            .build();
    when(notificationRepository.findRecipientUserIdsByTypeInAndEntity(
            Set.of(NotificationType.JOB_ORDER_CREATED), "STUB", entity))
        .thenReturn(List.of(B));
    when(ruleEvaluationService.resolveRecipients(event)).thenReturn(Map.of());

    Set<UUID> affected = flatten(service.createFromEvent(event));

    assertThat(affected).isEmpty();
    verify(notificationRepository, never())
        .deleteByTypeInAndEntityForRecipients(any(), any(), any(), any());
  }

  @Test
  void perRecipientSupersedeIsIgnoredWithoutNamedMembers() {
    StubNotificationEvent event =
        StubNotificationEvent.builder()
            .resolvesNotificationTypesForRecipients(Set.of(NotificationType.JOB_ORDER_CREATED))
            .build();
    when(ruleEvaluationService.resolveRecipients(event)).thenReturn(Map.of());

    service.createFromEvent(event);

    verify(notificationRepository, never())
        .findRecipientUserIdsByTypeInAndEntity(any(), any(), any());
  }

  @Test
  void perRecipientAndPerEntitySupersedeCanRunInOneEvent() {
    UUID entity = UUID.fromString("00000000-0000-0000-0000-00000000d001");
    StubNotificationEvent event =
        StubNotificationEvent.builder()
            .resolvesNotificationTypes(Set.of(NotificationType.JOB_ORDER_UPDATED_BY_REQUESTER))
            .resolvesNotificationTypesForRecipients(Set.of(NotificationType.JOB_ORDER_CREATED))
            .supersedeRecipients(Set.of(A))
            .build();
    when(notificationRepository.findRecipientUserIdsByTypeInAndEntity(
            Set.of(NotificationType.JOB_ORDER_UPDATED_BY_REQUESTER), "STUB", entity))
        .thenReturn(List.of(B));
    when(notificationRepository.findRecipientUserIdsByTypeInAndEntity(
            Set.of(NotificationType.JOB_ORDER_CREATED), "STUB", entity))
        .thenReturn(List.of(A, B));
    when(ruleEvaluationService.resolveRecipients(event)).thenReturn(Map.of());

    Set<UUID> affected = flatten(service.createFromEvent(event));

    assertThat(affected).containsExactlyInAnyOrder(A, B);
    verify(notificationRepository)
        .deleteByTypeInAndEntity(
            Set.of(NotificationType.JOB_ORDER_UPDATED_BY_REQUESTER), "STUB", entity);
    verify(notificationRepository)
        .deleteByTypeInAndEntityForRecipients(
            Set.of(NotificationType.JOB_ORDER_CREATED), "STUB", entity, Set.of(A));
  }

  /**
   * Unions every recipient reached by the call, regardless of signal.
   *
   * @param bySignal the call's result
   * @return the union of its recipient sets
   */
  private static Set<UUID> flatten(Map<NotificationSignal, Set<UUID>> bySignal) {
    Set<UUID> all = new HashSet<>();
    bySignal.values().forEach(all::addAll);
    return all;
  }
}
