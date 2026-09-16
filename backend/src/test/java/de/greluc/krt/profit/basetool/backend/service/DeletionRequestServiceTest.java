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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.event.AccountDeletionRequestDeclinedEvent;
import de.greluc.krt.profit.basetool.backend.event.AccountDeletionRequestResolvedEvent;
import de.greluc.krt.profit.basetool.backend.event.AccountDeletionRequestedEvent;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.DeletionRequest;
import de.greluc.krt.profit.basetool.backend.model.DeletionRequestStatus;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.DeletionRequestRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityNotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Mockito unit tests for {@link DeletionRequestService} — the members' Art. 17 erasure requests
 * (REQ-SEC-061).
 *
 * <p>Four properties carry the requirement and are each pinned down here: a member's click never
 * deletes anything; the request is idempotent even against a genuine race, because the guarantee is
 * a partial unique index rather than a check-then-act; a refusal cannot happen without a recorded
 * reason (Art. 12(4)); and carrying it out anonymises <b>before</b> deleting and reaches Keycloak
 * <b>after</b> the database half has committed (ADR-0111).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeletionRequestServiceTest {

  private static final UUID USER = UUID.randomUUID();
  private static final UUID REQUEST = UUID.randomUUID();
  private static final String HANDLE = "SomeCallsign";

  @Mock private DeletionRequestRepository deletionRequestRepository;
  @Mock private UserRepository userRepository;
  @Mock private UserDeletionService userDeletionService;
  @Mock private HandleAnonymisationService handleAnonymisationService;
  @Mock private KeycloakService keycloakService;
  @Mock private AuditService auditService;
  @Mock private AuthHelperService authHelperService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private ObjectProvider<DeletionRequestService> selfProvider;

  /** Real, not a mock: the assertions read the counter back rather than verifying a call. */
  private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

  private DeletionRequestService service;

  @BeforeEach
  void setUp() {
    service =
        new DeletionRequestService(
            deletionRequestRepository,
            userRepository,
            userDeletionService,
            handleAnonymisationService,
            keycloakService,
            auditService,
            authHelperService,
            eventPublisher,
            meterRegistry,
            selfProvider);
    // execute() reaches its transactional half through the proxy; in a unit test the proxy is the
    // instance itself.
    when(selfProvider.getObject()).thenReturn(service);

    User user = new User();
    user.setId(USER);
    user.setUsername(HANDLE);
    when(userRepository.findById(USER)).thenReturn(Optional.of(user));
    when(deletionRequestRepository.saveAndFlush(any()))
        .thenAnswer(inv -> inv.getArgument(0, DeletionRequest.class));
    when(deletionRequestRepository.save(any()))
        .thenAnswer(inv -> inv.getArgument(0, DeletionRequest.class));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(UUID.randomUUID()));
  }

  private DeletionRequest pending(boolean eraseHistory) {
    DeletionRequest request = new DeletionRequest(USER, eraseHistory);
    request.setId(REQUEST);
    return request;
  }

  // covers REQ-SEC-061 — raising a request audits it and tells the admins, and deletes nothing
  @Test
  void raisingARequestNeverDeletesAnything() {
    when(deletionRequestRepository.findByUserIdAndStatus(USER, DeletionRequestStatus.PENDING))
        .thenReturn(Optional.empty());

    DeletionRequest raised = service.raise(USER, true);

    assertThat(raised.getStatus()).isEqualTo(DeletionRequestStatus.PENDING);
    assertThat(raised.isEraseHistoryRequested()).isTrue();
    verify(auditService)
        .record(eq(AuditEventType.ACCOUNT_DELETION_REQUESTED), eq(USER), any(), eq(USER), any());
    verify(eventPublisher).publishEvent(any(AccountDeletionRequestedEvent.class));
    verifyNoInteractions(userDeletionService, keycloakService, handleAnonymisationService);
  }

  // covers REQ-SEC-061 — a second click returns the existing request instead of a second queue
  // entry
  @Test
  void raisingTwiceReturnsTheExistingRequest() {
    DeletionRequest existing = pending(false);
    when(deletionRequestRepository.findByUserIdAndStatus(USER, DeletionRequestStatus.PENDING))
        .thenReturn(Optional.of(existing));

    assertThat(service.raise(USER, true)).isSameAs(existing);

    verify(deletionRequestRepository, never()).saveAndFlush(any());
    verify(eventPublisher, never()).publishEvent(any(AccountDeletionRequestedEvent.class));
  }

  // covers REQ-SEC-061 — under a genuine race the partial unique index decides, not the pre-read
  @Test
  void aConcurrentRaiseResolvesToTheWinningRow() {
    DeletionRequest winner = pending(false);
    when(deletionRequestRepository.findByUserIdAndStatus(USER, DeletionRequestStatus.PENDING))
        .thenReturn(Optional.empty(), Optional.of(winner));
    when(deletionRequestRepository.saveAndFlush(any()))
        .thenThrow(new DataIntegrityViolationException("uq_deletion_request_one_pending_per_user"));

    assertThat(service.raise(USER, false)).isSameAs(winner);
    // Through the self-proxy both times, which is what puts the retry in a FRESH transaction. The
    // recovery used to be a catch inside the failed one, where the persistence context is
    // rollback-only and Postgres has already aborted the backend transaction (25P02), so the
    // re-read could not run and the member's second click answered 500.
    verify(selfProvider, times(2)).getObject();
  }

  // covers REQ-SEC-061 - the irreversible decision is read under a row lock and checks the echo
  @Test
  void decidingReadsTheRequestUnderARowLock() {
    // The execute path never writes deletion_request -- it audits, writes app_user, deletes the
    // user and lets the ON DELETE CASCADE take the row -- so Hibernate issues no versioned UPDATE
    // and @Version has nothing to compare. Without the lock both transactions read PENDING, the
    // member's withdrawal commits first, and the execution's cascade deletes the just-withdrawn
    // row along with the account: the member believes they took their request back and is deleted
    // anyway.
    when(deletionRequestRepository.findByIdForDecision(REQUEST))
        .thenReturn(Optional.of(pending(false)));

    service.execute(REQUEST, false, null);

    verify(deletionRequestRepository).findByIdForDecision(REQUEST);
    verify(deletionRequestRepository, never()).findById(REQUEST);
  }

  // covers REQ-SEC-061 - a stale queue page cannot decide an irreversible request
  @Test
  void aStaleClientVersionIs409RatherThanADeletion() {
    DeletionRequest request = pending(false);
    ReflectionTestUtils.setField(request, "version", 7L);
    when(deletionRequestRepository.findByIdForDecision(REQUEST)).thenReturn(Optional.of(request));

    assertThatThrownBy(() -> service.execute(REQUEST, false, 6L))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    assertThatThrownBy(() -> service.decline(REQUEST, "a reason", 6L))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    verify(userDeletionService, never()).deleteUser(any(), any());
  }

  // covers REQ-SEC-061 - a client that sends no version keeps the admin force-save semantics
  @Test
  void anAbsentClientVersionStillDecides() {
    DeletionRequest request = pending(false);
    ReflectionTestUtils.setField(request, "version", 7L);
    when(deletionRequestRepository.findByIdForDecision(REQUEST)).thenReturn(Optional.of(request));

    service.decline(REQUEST, "a reason", null);

    assertThat(request.getStatus()).isEqualTo(DeletionRequestStatus.DECLINED);
  }

  // covers REQ-SEC-061 - a race that outlives the bound is a truthful 409, never a silent success
  @Test
  void aRaceThatNeverResolvesPropagates() {
    when(deletionRequestRepository.findByUserIdAndStatus(USER, DeletionRequestStatus.PENDING))
        .thenReturn(Optional.empty());
    when(deletionRequestRepository.saveAndFlush(any()))
        .thenThrow(new DataIntegrityViolationException("uq_deletion_request_one_pending_per_user"));

    assertThatThrownBy(() -> service.raise(USER, false))
        .isInstanceOf(DataIntegrityViolationException.class);
    verify(deletionRequestRepository, times(3)).saveAndFlush(any());
  }

  // covers REQ-SEC-061 — a withdrawal is recorded, not erased
  @Test
  void withdrawingKeepsTheRowAsWithdrawn() {
    DeletionRequest request = pending(false);
    when(deletionRequestRepository.findByUserIdAndStatus(USER, DeletionRequestStatus.PENDING))
        .thenReturn(Optional.of(request));

    Optional<DeletionRequest> withdrawn = service.withdraw(USER);

    assertThat(withdrawn).isPresent();
    assertThat(withdrawn.get().getStatus()).isEqualTo(DeletionRequestStatus.WITHDRAWN);
    // Withdrawal used to publish nothing, so the request kept showing in every administrator's
    // bell -- with the member's name in it -- after the member took it back (REQ-NOTIF-018).
    verify(eventPublisher).publishEvent(any(AccountDeletionRequestResolvedEvent.class));
    assertThat(withdrawn.get().getDecidedAt()).isNotNull();
    verify(auditService)
        .record(
            eq(AuditEventType.ACCOUNT_DELETION_REQUEST_WITHDRAWN),
            eq(USER),
            any(),
            eq(USER),
            any());
  }

  @Test
  void withdrawingWithoutAPendingRequestIsANoOp() {
    when(deletionRequestRepository.findByUserIdAndStatus(USER, DeletionRequestStatus.PENDING))
        .thenReturn(Optional.empty());

    assertThat(service.withdraw(USER)).isEmpty();
    verifyNoInteractions(auditService);
  }

  // covers REQ-SEC-061 — Art. 12(4): a refusal the requester cannot be told about is not allowed
  @Test
  void refusingWithoutAReasonIsRejected() {
    assertThatThrownBy(() -> service.decline(REQUEST, "   ", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.decline(REQUEST, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(deletionRequestRepository);
  }

  // covers REQ-SEC-061 — a refusal records the reason and tells the member
  @Test
  void refusingRecordsTheReasonAndNotifiesTheMember() {
    when(deletionRequestRepository.findByIdForDecision(REQUEST))
        .thenReturn(Optional.of(pending(false)));

    DeletionRequest declined =
        service.decline(REQUEST, "Offene Buchung, bitte zuerst klaeren", null);

    assertThat(declined.getStatus()).isEqualTo(DeletionRequestStatus.DECLINED);
    assertThat(declined.getDecisionNote()).isNotBlank();
    assertThat(declined.getDecidedAt()).isNotNull();
    assertThat(declined.getDecidedById()).isNotNull();
    verify(eventPublisher).publishEvent(any(AccountDeletionRequestDeclinedEvent.class));
    // The refusal also clears the administrators' stale "member requests erasure" items, which
    // carry the member's handle in their render parameters (REQ-NOTIF-018).
    assertThat(new AccountDeletionRequestDeclinedEvent(USER).resolvesNotificationTypes())
        .containsExactly(NotificationType.ACCOUNT_DELETION_REQUESTED);
  }

  @Test
  void decidingAnAlreadyDecidedRequestIsRejected() {
    DeletionRequest done = pending(false);
    done.setStatus(DeletionRequestStatus.WITHDRAWN);
    when(deletionRequestRepository.findByIdForDecision(REQUEST)).thenReturn(Optional.of(done));

    assertThatThrownBy(() -> service.decline(REQUEST, "a reason", null))
        .isInstanceOf(EntityNotFoundException.class);
  }

  // covers REQ-SEC-061 — anonymise BEFORE the delete (the FK still points at the account) and reach
  // Keycloak AFTER the database half (ADR-0111)
  @Test
  void executingGrantsTheHistoryWishBeforeDeletingAndRemovesKeycloakLast() {
    when(deletionRequestRepository.findByIdForDecision(REQUEST))
        .thenReturn(Optional.of(pending(true)));

    service.execute(REQUEST, true, null);

    InOrder order =
        inOrder(handleAnonymisationService, userRepository, userDeletionService, keycloakService);
    // Every spelling the account carries, not the effective name alone: a handover or a
    // job-order contact is typed by hand and the typist wrote whichever name they use.
    order.verify(handleAnonymisationService).anonymise(eq(USER), anyList());
    order.verify(userRepository).saveAndFlush(any(User.class));
    order
        .verify(userDeletionService)
        .deleteUser(
            USER,
            UserDeletionService.KeycloakPresenceCheck.WAIVED_CALLER_REMOVES_THE_KEYCLOAK_USER);
    order.verify(keycloakService).deleteUser(USER);
  }

  // covers REQ-SEC-061, REQ-NOTIF-018 - the execution clears the administrators' stale items too
  @Test
  void executingResolvesTheAdministratorsPendingNotifications() {
    when(deletionRequestRepository.findByIdForDecision(REQUEST))
        .thenReturn(Optional.of(pending(false)));

    service.execute(REQUEST, false, null);

    // UserDeletionService removes notifications by RECIPIENT, and these recipients are other
    // people -- so without this the departed member's name sat in every administrator's inbox
    // payload until the 180-day unread sweep reaped it. Superseding beats rewriting the payload:
    // it happens on every path, not only when the history checkbox was ticked and granted.
    verify(eventPublisher).publishEvent(any(AccountDeletionRequestResolvedEvent.class));
  }

  // covers REQ-SEC-061 — the wish is not an instruction: an ungranted wish anonymises nothing
  @Test
  void executingWithoutGrantingLeavesTheHandleSnapshotsAlone() {
    when(deletionRequestRepository.findByIdForDecision(REQUEST))
        .thenReturn(Optional.of(pending(true)));

    service.execute(REQUEST, false, null);

    verifyNoInteractions(handleAnonymisationService);
    verify(userDeletionService).deleteUser(eq(USER), any());
    verify(keycloakService).deleteUser(USER);
  }

  // covers REQ-SEC-061 — a Keycloak failure must not undo or mask the committed local deletion
  @Test
  void aFailingKeycloakDeleteDoesNotUndoTheLocalDeletion() {
    when(deletionRequestRepository.findByIdForDecision(REQUEST))
        .thenReturn(Optional.of(pending(false)));
    Mockito.doThrow(new IllegalStateException("keycloak down"))
        .when(keycloakService)
        .deleteUser(USER);

    // Must not propagate: the member's data is gone, which is what they asked for.
    service.execute(REQUEST, false, null);

    verify(userDeletionService).deleteUser(eq(USER), any());
  }

  /**
   * The half-finished erasure has to be findable afterwards.
   *
   * <p>It used to leave a {@code log.warn} and nothing else. The surviving Keycloak account cannot
   * show up in the REQ-SEC-059 orphan gauge — that counts a local row whose Keycloak account has
   * gone, and this account has no local row left at all — so the counter and the audit row are the
   * only signals there are. The audit row carries a {@code null} target because {@code
   * target_user_id} is a foreign key to an {@code app_user} row that has already been deleted, and
   * only the exception's class name, because its message can echo Keycloak's own view of the
   * account.
   */
  @Test
  void aFailingKeycloakDeleteLeavesACounterAndAnAuditRow() {
    when(deletionRequestRepository.findByIdForDecision(REQUEST))
        .thenReturn(Optional.of(pending(false)));
    Mockito.doThrow(new IllegalStateException("keycloak down"))
        .when(keycloakService)
        .deleteUser(USER);

    service.execute(REQUEST, false, null);

    assertEquals(
        1.0,
        meterRegistry.counter(MetricNames.ACCOUNT_DELETION_KEYCLOAK_FAILURES).count(),
        "a swallowed Keycloak delete must bump the counter its alert reads");
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditService)
        .record(
            eq(AuditEventType.ACCOUNT_DELETION_KEYCLOAK_DELETE_FAILED),
            eq(USER),
            isNull(),
            isNull(),
            details.capture());
    String payload = details.getValue().toString();
    assertTrue(
        payload.contains("error=IllegalStateException"),
        "the payload names the failure's class: " + payload);
    assertFalse(
        payload.contains("keycloak down"),
        "the exception message never goes into the details payload: " + payload);
  }

  /** A successful Keycloak delete leaves neither the counter nor the failure row. */
  @Test
  void aSucceedingKeycloakDeleteRecordsNoFailure() {
    when(deletionRequestRepository.findByIdForDecision(REQUEST))
        .thenReturn(Optional.of(pending(false)));

    service.execute(REQUEST, false, null);

    assertEquals(
        0.0, meterRegistry.counter(MetricNames.ACCOUNT_DELETION_KEYCLOAK_FAILURES).count());
    verify(auditService, never())
        .record(
            eq(AuditEventType.ACCOUNT_DELETION_KEYCLOAK_DELETE_FAILED), any(), any(), any(), any());
  }
}
