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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemCode;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

/** The appointment of the Handelsbeauftragte and their limits (REQ-PUR-003, REQ-PUR-025). */
@ExtendWith(MockitoExtension.class)
class PurchasingDelegateServiceTest {

  @Mock private PurchasingDelegateRepository delegateRepository;
  @Mock private UserRepository userRepository;
  @Mock private PurchasingSettingsService settingsService;
  @Mock private PurchasingAccessPolicy accessPolicy;
  @Mock private AuthHelperService authHelperService;
  @Mock private AuditRecorder auditRecorder;
  @Mock private ApplicationEventPublisher eventPublisher;

  private PurchasingDelegateService service;

  @BeforeEach
  void setUp() {
    service =
        new PurchasingDelegateService(
            delegateRepository,
            userRepository,
            settingsService,
            accessPolicy,
            authHelperService,
            auditRecorder,
            eventPublisher);
  }

  private static User user(UUID id, String displayName) {
    User user = new User();
    user.setId(id);
    user.setDisplayName(displayName);
    return user;
  }

  private static PurchasingDelegate delegate(UUID id, UUID userId, long version) {
    PurchasingDelegate delegate = new PurchasingDelegate();
    delegate.setId(id);
    delegate.setUserId(userId);
    delegate.setVersion(version);
    delegate.setAppointedAt(Instant.now().minus(Duration.ofDays(3)));
    return delegate;
  }

  private void stubDefaultLimit(long limit) {
    PurchasingSetting setting = new PurchasingSetting();
    setting.setDefaultMonthlyLimit(limit);
    when(settingsService.current()).thenReturn(setting);
  }

  private void governing() {
    when(accessPolicy.canAppointDelegates()).thenReturn(true);
  }

  @Test
  void listIsRefusedForACallerWhoDoesNotGovern() {
    when(accessPolicy.canAppointDelegates()).thenReturn(false);

    assertThatThrownBy(() -> service.list()).isInstanceOf(AccessDeniedException.class);

    verifyNoInteractions(delegateRepository);
  }

  @Test
  void listResolvesNamesAndTheEffectiveLimit() {
    governing();
    stubDefaultLimit(1_000L);
    UUID memberId = UUID.randomUUID();
    UUID adminId = UUID.randomUUID();
    PurchasingDelegate active = delegate(UUID.randomUUID(), memberId, 1L);
    active.setAppointedBy(adminId);
    PurchasingDelegate erased = delegate(UUID.randomUUID(), null, 2L);
    erased.setMonthlyLimit(500L);
    erased.setRevokedAt(Instant.now().minus(Duration.ofDays(1)));
    when(delegateRepository.findAllByOrderByAppointedAtDesc()).thenReturn(List.of(active, erased));
    when(userRepository.findAllById(any()))
        .thenReturn(List.of(user(memberId, "Alice"), user(adminId, "Boss")));

    List<DelegateDto> dtos = service.list();

    assertThat(dtos).hasSize(2);
    assertThat(dtos.get(0).displayName()).isEqualTo("Alice");
    assertThat(dtos.get(0).appointedByName()).isEqualTo("Boss");
    assertThat(dtos.get(0).monthlyLimit()).isNull();
    assertThat(dtos.get(0).effectiveLimit()).isEqualTo(1_000L);
    assertThat(dtos.get(0).active()).isTrue();
    assertThat(dtos.get(1).userId()).isNull();
    assertThat(dtos.get(1).displayName()).isNull();
    assertThat(dtos.get(1).effectiveLimit()).isEqualTo(500L);
    assertThat(dtos.get(1).active()).isFalse();
  }

  @Test
  void appointingStoresTheAppointmentAuditsItAndTellsTheMember() {
    governing();
    stubDefaultLimit(1_000L);
    UUID memberId = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    UUID delegateId = UUID.randomUUID();
    when(userRepository.findById(memberId)).thenReturn(Optional.of(user(memberId, "Alice")));
    when(delegateRepository.findFirstByUserIdAndRevokedAtIsNull(memberId))
        .thenReturn(Optional.empty());
    when(authHelperService.currentUserId()).thenReturn(Optional.of(actor));
    when(delegateRepository.saveAndFlush(any(PurchasingDelegate.class)))
        .thenAnswer(
            invocation -> {
              PurchasingDelegate saved = invocation.getArgument(0);
              saved.setId(delegateId);
              return saved;
            });
    when(userRepository.findAllById(any())).thenReturn(List.of(user(memberId, "Alice")));
    Instant until = Instant.now().plus(Duration.ofDays(30));

    DelegateDto dto = service.appoint(new AppointDelegateRequest(memberId, until, 2_500L));

    assertThat(dto.id()).isEqualTo(delegateId);
    assertThat(dto.userId()).isEqualTo(memberId);
    assertThat(dto.displayName()).isEqualTo("Alice");
    assertThat(dto.monthlyLimit()).isEqualTo(2_500L);
    assertThat(dto.active()).isTrue();
    ArgumentCaptor<PurchasingDelegate> stored = ArgumentCaptor.forClass(PurchasingDelegate.class);
    verify(delegateRepository).saveAndFlush(stored.capture());
    assertThat(stored.getValue().getAppointedBy()).isEqualTo(actor);
    assertThat(stored.getValue().getValidUntil()).isEqualTo(until);
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.DELEGATE_APPOINTED),
            eq(delegateId),
            eq("Handelsbeauftragte"),
            eq(memberId),
            details.capture());
    assertThat(details.getValue().toString()).contains("limit=2500", "validUntil=" + until);
    ArgumentCaptor<NoticeEvent> notice = ArgumentCaptor.forClass(NoticeEvent.class);
    verify(eventPublisher).publishEvent(notice.capture());
    assertThat(notice.getValue().eventType())
        .isEqualTo(NotificationEventType.PURCHASING_DELEGATE_APPOINTED);
    assertThat(notice.getValue().contextRecipientUserId()).isEqualTo(memberId);
    assertThat(notice.getValue().actorSub()).isEqualTo(actor);
    assertThat(notice.getValue().entityId()).isEqualTo(delegateId);
  }

  @Test
  void appointingAnExpiredOpenAppointmentRevokesItFirst() {
    governing();
    stubDefaultLimit(1_000L);
    UUID memberId = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    PurchasingDelegate expired = delegate(UUID.randomUUID(), memberId, 1L);
    expired.setValidUntil(Instant.now().minus(Duration.ofDays(1)));
    when(userRepository.findById(memberId)).thenReturn(Optional.of(user(memberId, "Alice")));
    when(delegateRepository.findFirstByUserIdAndRevokedAtIsNull(memberId))
        .thenReturn(Optional.of(expired));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(actor));
    when(delegateRepository.saveAndFlush(any(PurchasingDelegate.class)))
        .thenAnswer(
            invocation -> {
              PurchasingDelegate saved = invocation.getArgument(0);
              if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
              }
              return saved;
            });

    service.appoint(new AppointDelegateRequest(memberId, null, null));

    assertThat(expired.getRevokedAt()).isNotNull();
    assertThat(expired.getRevokedBy()).isEqualTo(actor);
    verify(delegateRepository, times(2)).saveAndFlush(any(PurchasingDelegate.class));
    verify(auditRecorder)
        .record(eq(AuditEventType.DELEGATE_APPOINTED), any(), any(), eq(memberId), any());
  }

  @Test
  void aMemberWithAnActiveAppointmentCannotBeAppointedAgain() {
    governing();
    UUID memberId = UUID.randomUUID();
    when(userRepository.findById(memberId)).thenReturn(Optional.of(user(memberId, "Alice")));
    when(delegateRepository.findFirstByUserIdAndRevokedAtIsNull(memberId))
        .thenReturn(Optional.of(delegate(UUID.randomUUID(), memberId, 1L)));

    assertThatThrownBy(() -> service.appoint(new AppointDelegateRequest(memberId, null, null)))
        .isInstanceOfSatisfying(
            PurchasingProblemException.class,
            e ->
                assertThat(e.getProblemCode())
                    .isEqualTo(PurchasingProblemCode.PURCHASING_DELEGATE_ACTIVE));

    verify(delegateRepository, never()).saveAndFlush(any());
    verify(auditRecorder, never()).record(any(), any(), any(), any(), any());
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void anAppointmentEndingInThePastIsABadRequest() {
    governing();
    UUID memberId = UUID.randomUUID();
    when(userRepository.findById(memberId)).thenReturn(Optional.of(user(memberId, "Alice")));
    Instant past = Instant.now().minus(Duration.ofMinutes(1));

    assertThatThrownBy(() -> service.appoint(new AppointDelegateRequest(memberId, past, null)))
        .isInstanceOf(BadRequestException.class);

    verify(delegateRepository, never()).saveAndFlush(any());
  }

  @Test
  void appointingAnUnknownMemberIsNotFound() {
    governing();
    UUID memberId = UUID.randomUUID();
    when(userRepository.findById(memberId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.appoint(new AppointDelegateRequest(memberId, null, null)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void appointingIsRefusedForACallerWhoDoesNotGovern() {
    when(accessPolicy.canAppointDelegates()).thenReturn(false);

    assertThatThrownBy(
            () -> service.appoint(new AppointDelegateRequest(UUID.randomUUID(), null, null)))
        .isInstanceOf(AccessDeniedException.class);

    verifyNoInteractions(userRepository, delegateRepository, eventPublisher);
  }

  @Test
  void revokingStampsTheRevocationAuditsItAndTellsTheMember() {
    governing();
    stubDefaultLimit(1_000L);
    UUID memberId = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    PurchasingDelegate delegate = delegate(UUID.randomUUID(), memberId, 2L);
    when(delegateRepository.findById(delegate.getId())).thenReturn(Optional.of(delegate));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(actor));
    when(delegateRepository.saveAndFlush(delegate)).thenReturn(delegate);

    DelegateDto dto = service.revoke(delegate.getId(), 2L);

    assertThat(delegate.getRevokedAt()).isNotNull();
    assertThat(delegate.getRevokedBy()).isEqualTo(actor);
    assertThat(dto.active()).isFalse();
    verify(auditRecorder)
        .record(
            eq(AuditEventType.DELEGATE_REVOKED),
            eq(delegate.getId()),
            eq("Handelsbeauftragte"),
            eq(memberId),
            isNull());
    ArgumentCaptor<NoticeEvent> notice = ArgumentCaptor.forClass(NoticeEvent.class);
    verify(eventPublisher).publishEvent(notice.capture());
    assertThat(notice.getValue().eventType())
        .isEqualTo(NotificationEventType.PURCHASING_DELEGATE_REVOKED);
    assertThat(notice.getValue().contextRecipientUserId()).isEqualTo(memberId);
    assertThat(notice.getValue().resolvesNotificationTypes())
        .containsExactly(NotificationType.PURCHASING_DELEGATE_APPOINTED);
  }

  @Test
  void revokingAnErasedMemberAuditsButPublishesNoNotice() {
    governing();
    stubDefaultLimit(1_000L);
    PurchasingDelegate delegate = delegate(UUID.randomUUID(), null, 0L);
    when(delegateRepository.findById(delegate.getId())).thenReturn(Optional.of(delegate));
    when(delegateRepository.saveAndFlush(delegate)).thenReturn(delegate);

    service.revoke(delegate.getId(), 0L);

    verify(auditRecorder)
        .record(
            eq(AuditEventType.DELEGATE_REVOKED), eq(delegate.getId()), any(), isNull(), isNull());
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void anAlreadyRevokedAppointmentCannotBeRevokedAgain() {
    governing();
    PurchasingDelegate delegate = delegate(UUID.randomUUID(), UUID.randomUUID(), 1L);
    delegate.setRevokedAt(Instant.now().minus(Duration.ofDays(1)));
    when(delegateRepository.findById(delegate.getId())).thenReturn(Optional.of(delegate));

    assertThatThrownBy(() -> service.revoke(delegate.getId(), 1L))
        .isInstanceOf(BadRequestException.class);

    verify(delegateRepository, never()).saveAndFlush(any());
    verify(auditRecorder, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void revokingWithAStaleVersionIsAConflict() {
    governing();
    PurchasingDelegate delegate = delegate(UUID.randomUUID(), UUID.randomUUID(), 4L);
    when(delegateRepository.findById(delegate.getId())).thenReturn(Optional.of(delegate));

    assertThatThrownBy(() -> service.revoke(delegate.getId(), 3L))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    verify(delegateRepository, never()).saveAndFlush(any());
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void changingTheLimitAuditsTheNewAndThePreviousValue() {
    governing();
    stubDefaultLimit(1_000L);
    UUID memberId = UUID.randomUUID();
    PurchasingDelegate delegate = delegate(UUID.randomUUID(), memberId, 1L);
    delegate.setMonthlyLimit(100L);
    when(delegateRepository.findById(delegate.getId())).thenReturn(Optional.of(delegate));
    when(delegateRepository.saveAndFlush(delegate)).thenReturn(delegate);

    DelegateDto dto = service.setLimit(delegate.getId(), new UpdateDelegateLimitRequest(300L, 1L));

    assertThat(delegate.getMonthlyLimit()).isEqualTo(300L);
    assertThat(dto.effectiveLimit()).isEqualTo(300L);
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.DELEGATE_LIMIT_CHANGED),
            eq(delegate.getId()),
            eq("Handelsbeauftragte"),
            eq(memberId),
            details.capture());
    assertThat(details.getValue().toString()).contains("limit=300", "wasLimit=100");
  }

  @Test
  void clearingThePersonalLimitFallsBackToTheDefault() {
    governing();
    stubDefaultLimit(1_000L);
    PurchasingDelegate delegate = delegate(UUID.randomUUID(), UUID.randomUUID(), 1L);
    delegate.setMonthlyLimit(100L);
    when(delegateRepository.findById(delegate.getId())).thenReturn(Optional.of(delegate));
    when(delegateRepository.saveAndFlush(delegate)).thenReturn(delegate);

    DelegateDto dto = service.setLimit(delegate.getId(), new UpdateDelegateLimitRequest(null, 1L));

    assertThat(dto.monthlyLimit()).isNull();
    assertThat(dto.effectiveLimit()).isEqualTo(1_000L);
  }

  @Test
  void changingTheLimitWithAStaleVersionIsAConflict() {
    governing();
    PurchasingDelegate delegate = delegate(UUID.randomUUID(), UUID.randomUUID(), 2L);
    when(delegateRepository.findById(delegate.getId())).thenReturn(Optional.of(delegate));

    assertThatThrownBy(
            () -> service.setLimit(delegate.getId(), new UpdateDelegateLimitRequest(300L, 1L)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    verify(auditRecorder, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void theActiveDelegateIdsAreReturnedAsASet() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    when(delegateRepository.findActiveUserIds(any(Instant.class)))
        .thenReturn(List.of(first, second, first));

    assertThat(service.activeDelegateIds()).isEqualTo(Set.of(first, second));
  }

  @Test
  void theEffectiveLimitIsThePersonalOneElseTheDefault() {
    stubDefaultLimit(1_000L);
    UUID withLimit = UUID.randomUUID();
    UUID withoutLimit = UUID.randomUUID();
    UUID withoutAppointment = UUID.randomUUID();
    PurchasingDelegate personal = delegate(UUID.randomUUID(), withLimit, 0L);
    personal.setMonthlyLimit(250L);
    when(delegateRepository.findFirstByUserIdAndRevokedAtIsNull(withLimit))
        .thenReturn(Optional.of(personal));
    when(delegateRepository.findFirstByUserIdAndRevokedAtIsNull(withoutLimit))
        .thenReturn(Optional.of(delegate(UUID.randomUUID(), withoutLimit, 0L)));
    when(delegateRepository.findFirstByUserIdAndRevokedAtIsNull(withoutAppointment))
        .thenReturn(Optional.empty());

    assertThat(service.effectiveLimit(withLimit)).isEqualTo(250L);
    assertThat(service.effectiveLimit(withoutLimit)).isEqualTo(1_000L);
    assertThat(service.effectiveLimit(withoutAppointment)).isEqualTo(1_000L);
  }
}
