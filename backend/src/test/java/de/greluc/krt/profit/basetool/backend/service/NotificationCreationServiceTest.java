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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.event.BankBookingRequestCancelledEvent;
import de.greluc.krt.profit.basetool.backend.event.BankBookingRequestConfirmedEvent;
import de.greluc.krt.profit.basetool.backend.event.JobOrderCreatedEvent;
import de.greluc.krt.profit.basetool.backend.event.OrgUnitRef;
import de.greluc.krt.profit.basetool.backend.model.Notification;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import de.greluc.krt.profit.basetool.backend.support.NotificationParamsCodec;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
  @InjectMocks private NotificationCreationService service;

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

    // #1152: createFromEvent now returns the deduped recipients (the listener publishes to them
    // after commit) rather than a row count; the row count stays covered by the saveAll capture.
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
    assertThat(saved).extracting(Notification::getRecipientSub).containsExactlyInAnyOrder(A, B);
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
    // REQ-NOTIF-018: an event that supersedes nothing (JobOrderCreatedEvent) never runs the
    // supersede find/delete — its resolvesNotificationTypes() is empty.
    JobOrderCreatedEvent event = event();
    when(ruleEvaluationService.resolveRecipients(event)).thenReturn(Map.of());

    service.createFromEvent(event);

    verify(notificationRepository, never()).findRecipientSubsByTypeInAndEntity(any(), any(), any());
    verify(notificationRepository, never()).deleteByTypeInAndEntity(any(), any(), any());
  }

  @Test
  void decisionEventRemovesSupersededCreatedNotificationsAndReturnsTheirRecipients() {
    // REQ-NOTIF-018: confirming a booking request clears the BANK_BOOKING_REQUEST_CREATED items the
    // staff were shown and notifies the requester; the returned set is the union of both so both
    // inboxes refresh live.
    UUID requestId = UUID.fromString("00000000-0000-0000-0000-00000000e777");
    UUID requester = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    UUID staffA = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    UUID staffB = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    BankBookingRequestConfirmedEvent event =
        new BankBookingRequestConfirmedEvent(
            requestId, UUID.randomUUID(), "KB-0001", new BigDecimal("500"), requester, staffA);
    Set<NotificationType> superseded = Set.of(NotificationType.BANK_BOOKING_REQUEST_CREATED);
    when(notificationRepository.findRecipientSubsByTypeInAndEntity(
            superseded, "BANK_BOOKING_REQUEST", requestId))
        .thenReturn(List.of(staffA, staffB));
    when(notificationRepository.deleteByTypeInAndEntity(
            superseded, "BANK_BOOKING_REQUEST", requestId))
        .thenReturn(2);
    when(ruleEvaluationService.resolveRecipients(event))
        .thenReturn(Map.of(NotificationType.BANK_BOOKING_REQUEST_CONFIRMED, Set.of(requester)));
    when(notificationParamsCodec.serialize(any())).thenReturn("{}");

    Set<UUID> affected = flatten(service.createFromEvent(event));

    // The removed-notification holders (staff) plus the new-notification recipient (requester).
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
              assertThat(n.getRecipientSub()).isEqualTo(requester);
            });
  }

  @Test
  void withdrawalEventOnlyRemovesAndCreatesNothing() {
    // REQ-NOTIF-018: a cancel notifies nobody (empty rule result) but still clears the staff's
    // stale created-notifications and returns their subs so their badge refreshes.
    UUID requestId = UUID.fromString("00000000-0000-0000-0000-00000000e888");
    UUID staff = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    BankBookingRequestCancelledEvent event =
        new BankBookingRequestCancelledEvent(requestId, UUID.randomUUID(), UUID.randomUUID());
    Set<NotificationType> superseded = Set.of(NotificationType.BANK_BOOKING_REQUEST_CREATED);
    when(notificationRepository.findRecipientSubsByTypeInAndEntity(
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
    // No stale rows → no delete issued, no phantom recipients returned.
    UUID requestId = UUID.fromString("00000000-0000-0000-0000-00000000e999");
    BankBookingRequestCancelledEvent event =
        new BankBookingRequestCancelledEvent(requestId, UUID.randomUUID(), UUID.randomUUID());
    when(notificationRepository.findRecipientSubsByTypeInAndEntity(
            eq(Set.of(NotificationType.BANK_BOOKING_REQUEST_CREATED)),
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
   * Every recipient the call reached, whatever they were told.
   *
   * <p>The result is keyed by signal now, because one event can raise different notification types
   * for different audiences. These assertions are about *who* was reached, which is the question
   * they were always asking; the signal itself is asserted where it matters, in {@code
   * NotificationEventListenerTest}.
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
