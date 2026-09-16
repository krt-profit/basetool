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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.event.AccountDeletionRequestDeclinedEvent;
import de.greluc.krt.profit.basetool.backend.event.AccountDeletionRequestedEvent;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.DeletionRequest;
import de.greluc.krt.profit.basetool.backend.model.DeletionRequestStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.DeletionRequestRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

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
    assertThatThrownBy(() -> service.decline(REQUEST, "   "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.decline(REQUEST, null))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(deletionRequestRepository);
  }

  // covers REQ-SEC-061 — a refusal records the reason and tells the member
  @Test
  void refusingRecordsTheReasonAndNotifiesTheMember() {
    when(deletionRequestRepository.findById(REQUEST)).thenReturn(Optional.of(pending(false)));

    DeletionRequest declined = service.decline(REQUEST, "Offene Buchung, bitte zuerst klaeren");

    assertThat(declined.getStatus()).isEqualTo(DeletionRequestStatus.DECLINED);
    assertThat(declined.getDecisionNote()).isNotBlank();
    assertThat(declined.getDecidedAt()).isNotNull();
    assertThat(declined.getDecidedById()).isNotNull();
    verify(eventPublisher).publishEvent(any(AccountDeletionRequestDeclinedEvent.class));
  }

  @Test
  void decidingAnAlreadyDecidedRequestIsRejected() {
    DeletionRequest done = pending(false);
    done.setStatus(DeletionRequestStatus.WITHDRAWN);
    when(deletionRequestRepository.findById(REQUEST)).thenReturn(Optional.of(done));

    assertThatThrownBy(() -> service.decline(REQUEST, "a reason"))
        .isInstanceOf(EntityNotFoundException.class);
  }

  // covers REQ-SEC-061 — anonymise BEFORE the delete (the FK still points at the account) and reach
  // Keycloak AFTER the database half (ADR-0111)
  @Test
  void executingGrantsTheHistoryWishBeforeDeletingAndRemovesKeycloakLast() {
    when(deletionRequestRepository.findById(REQUEST)).thenReturn(Optional.of(pending(true)));

    service.execute(REQUEST, true, "Wunsch gewaehrt");

    InOrder order =
        inOrder(handleAnonymisationService, userRepository, userDeletionService, keycloakService);
    order.verify(handleAnonymisationService).anonymise(USER, HANDLE);
    order.verify(userRepository).saveAndFlush(any(User.class));
    order
        .verify(userDeletionService)
        .deleteUser(
            USER,
            UserDeletionService.KeycloakPresenceCheck.WAIVED_CALLER_REMOVES_THE_KEYCLOAK_USER);
    order.verify(keycloakService).deleteUser(USER);
  }

  // covers REQ-SEC-061 — the wish is not an instruction: an ungranted wish anonymises nothing
  @Test
  void executingWithoutGrantingLeavesTheHandleSnapshotsAlone() {
    when(deletionRequestRepository.findById(REQUEST)).thenReturn(Optional.of(pending(true)));

    service.execute(REQUEST, false, null);

    verifyNoInteractions(handleAnonymisationService);
    verify(userDeletionService).deleteUser(eq(USER), any());
    verify(keycloakService).deleteUser(USER);
  }

  // covers REQ-SEC-061 — a Keycloak failure must not undo or mask the committed local deletion
  @Test
  void aFailingKeycloakDeleteDoesNotUndoTheLocalDeletion() {
    when(deletionRequestRepository.findById(REQUEST)).thenReturn(Optional.of(pending(false)));
    Mockito.doThrow(new RuntimeException("keycloak down")).when(keycloakService).deleteUser(USER);

    // Must not propagate: the member's data is gone, which is what they asked for.
    service.execute(REQUEST, false, null);

    verify(userDeletionService).deleteUser(eq(USER), any());
  }
}
