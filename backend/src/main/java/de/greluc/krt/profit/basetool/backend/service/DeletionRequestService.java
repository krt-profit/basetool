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

import de.greluc.krt.profit.basetool.backend.event.AccountDeletionRequestDeclinedEvent;
import de.greluc.krt.profit.basetool.backend.event.AccountDeletionRequestedEvent;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.DeletionRequest;
import de.greluc.krt.profit.basetool.backend.model.DeletionRequestStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.DeletionRequestRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import jakarta.persistence.EntityNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The members' Art. 17 erasure requests, and the admin decisions on them (REQ-SEC-061).
 *
 * <p>A member raises a request on their own profile; an admin decides it. <b>Nothing here deletes
 * an account as a side effect of the member's click</b> — that is the whole design (decision 5,
 *
 * @greluc, ADR-0181). The deletion removes the Keycloak account, purges the member's warehouse
 *     stock and hangar and reassigns their missions and refinery orders (REQ-DATA-008); none of it
 *     is reversible, and a mis-click on one's own profile page must not be able to start it.
 *     <p>Modelled on the registration-approval queue rather than a second pattern: a row per
 *     request, a status, a decider, a decision instant and a recorded reason.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeletionRequestService {

  private final DeletionRequestRepository deletionRequestRepository;
  private final UserRepository userRepository;
  private final UserDeletionService userDeletionService;
  private final HandleAnonymisationService handleAnonymisationService;
  private final KeycloakService keycloakService;
  private final AuditService auditService;
  private final AuthHelperService authHelperService;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * Self-injection (lazy {@link ObjectProvider} to avoid an eager construction cycle) so {@link
   * #execute} can call its own {@code @Transactional} database half through the proxy. A direct
   * {@code this.} call would bypass the transaction advice entirely, which is the bug this pattern
   * exists to prevent.
   */
  private final ObjectProvider<DeletionRequestService> selfProvider;

  /**
   * Raises a member's erasure request.
   *
   * <p>Idempotent by design rather than by check-then-act: a partial unique index on {@code
   * (user_id) WHERE status = 'PENDING'} is what guarantees one open request per member, so a member
   * who double-clicks gets their existing request back instead of a second queue entry. The
   * pre-read is there to answer the common case without provoking a constraint violation; the
   * {@code catch} is what makes it correct under a genuine race.
   *
   * @param userId the member asking to be erased
   * @param eraseHistoryRequested whether they also ask for the surviving handle snapshots to be
   *     anonymised — a wish an admin decides deliberately, never an instruction
   * @return the member's open request, newly created or pre-existing
   */
  @Transactional
  public @NotNull DeletionRequest raise(@NotNull UUID userId, boolean eraseHistoryRequested) {
    Optional<DeletionRequest> existing =
        deletionRequestRepository.findByUserIdAndStatus(userId, DeletionRequestStatus.PENDING);
    if (existing.isPresent()) {
      return existing.get();
    }
    DeletionRequest saved;
    try {
      saved =
          deletionRequestRepository.saveAndFlush(
              new DeletionRequest(userId, eraseHistoryRequested));
    } catch (DataIntegrityViolationException e) {
      // The partial unique index fired: a concurrent click won. Its row is the answer.
      log.debug("Concurrent deletion request for {}; returning the winner's row", userId);
      return deletionRequestRepository
          .findByUserIdAndStatus(userId, DeletionRequestStatus.PENDING)
          .orElseThrow(() -> e);
    }

    auditService.record(
        AuditEventType.ACCOUNT_DELETION_REQUESTED,
        userId,
        handleOf(userId),
        userId,
        AuditDetails.of("eraseHistoryRequested", eraseHistoryRequested));

    // Published after the row is flushed so the admins' notification cannot describe a request that
    // then rolls back (REQ-NOTIF-002 produces after commit).
    eventPublisher.publishEvent(new AccountDeletionRequestedEvent(userId, handleOf(userId)));
    log.info("Member {} raised an account-deletion request", userId);
    return saved;
  }

  /**
   * Takes a member's own pending request back.
   *
   * <p>The row is kept in {@link DeletionRequestStatus#WITHDRAWN} rather than deleted: "asked and
   * changed their mind" is a different fact from "never asked", and it is the difference an admin
   * needs when a second request arrives from the same member.
   *
   * @param userId the member withdrawing their request
   * @return the withdrawn request, or empty when the member had no pending one
   */
  @Transactional
  public @NotNull Optional<DeletionRequest> withdraw(@NotNull UUID userId) {
    Optional<DeletionRequest> pending =
        deletionRequestRepository.findByUserIdAndStatus(userId, DeletionRequestStatus.PENDING);
    if (pending.isEmpty()) {
      return Optional.empty();
    }
    DeletionRequest request = pending.get();
    request.setStatus(DeletionRequestStatus.WITHDRAWN);
    request.setDecidedAt(Instant.now());
    deletionRequestRepository.save(request);

    auditService.record(
        AuditEventType.ACCOUNT_DELETION_REQUEST_WITHDRAWN,
        userId,
        handleOf(userId),
        userId,
        AuditDetails.of("requestId", request.getId()));
    log.info("Member {} withdrew their account-deletion request", userId);
    return Optional.of(request);
  }

  /**
   * Refuses a request, recording the admin's reasoning.
   *
   * <p>The note is mandatory and the database enforces it too: Art. 12(4) requires telling the
   * requester <em>why</em> a request is refused, together with their right to complain and to a
   * judicial remedy, and a reason nobody wrote down cannot be told to them. It is stored on the
   * request row and <b>not</b> in the audit details payload, which carries no user free text
   * (REQ-AUDIT-001).
   *
   * @param requestId the request to refuse
   * @param note the admin's reasoning; must not be blank
   * @return the refused request
   * @throws EntityNotFoundException when no such pending request exists
   * @throws IllegalArgumentException when the note is blank
   */
  @Transactional
  public @NotNull DeletionRequest decline(@NotNull UUID requestId, @Nullable String note) {
    if (note == null || note.isBlank()) {
      throw new IllegalArgumentException("A declined deletion request must carry a reason");
    }
    DeletionRequest request = pendingOrThrow(requestId);
    request.setStatus(DeletionRequestStatus.DECLINED);
    request.setDecidedAt(Instant.now());
    request.setDecidedById(authHelperService.currentUserId().orElse(null));
    request.setDecisionNote(note);
    deletionRequestRepository.save(request);

    auditService.record(
        AuditEventType.ACCOUNT_DELETION_REQUEST_DECLINED,
        request.getUserId(),
        handleOf(request.getUserId()),
        request.getUserId(),
        AuditDetails.of("requestId", request.getId()));

    eventPublisher.publishEvent(new AccountDeletionRequestDeclinedEvent(request.getUserId()));
    log.info("Deletion request {} declined", requestId);
    return request;
  }

  /**
   * The member's own open request, if they have one.
   *
   * @param userId the member
   * @return their pending request, or empty
   */
  @Transactional(readOnly = true)
  public @NotNull Optional<DeletionRequest> findPending(@NotNull UUID userId) {
    return deletionRequestRepository.findByUserIdAndStatus(userId, DeletionRequestStatus.PENDING);
  }

  /**
   * The member's most recent request, whatever its status — what their profile page shows.
   *
   * <p>Not restricted to pending: a refused request carries the reasoning the member has a right to
   * read (Art. 12(4)), and a withdrawn one is why the page offers to raise a new one rather than
   * pretending nothing happened.
   *
   * @param userId the member
   * @return their latest request, or empty
   */
  @Transactional(readOnly = true)
  public @NotNull Optional<DeletionRequest> findLatest(@NotNull UUID userId) {
    return deletionRequestRepository.findFirstByUserIdOrderByCreatedAtDesc(userId);
  }

  /**
   * Lists the open requests, oldest first.
   *
   * @return the pending requests
   */
  @Transactional(readOnly = true)
  public @NotNull List<DeletionRequest> listPending() {
    return deletionRequestRepository.findByStatusOrderByCreatedAtAsc(DeletionRequestStatus.PENDING);
  }

  /**
   * Carries a request out: optionally anonymises the surviving handle snapshots, then deletes the
   * account — the local row first and the Keycloak user last.
   *
   * <p><b>Both halves, one click.</b> An admin deciding this request has decided to delete this
   * specific account, so the application removes the Keycloak user itself instead of asking the
   * admin to do it in the Keycloak console and come back after the nightly roster sync. Leaving the
   * second act to a human is exactly the state REQ-SEC-059 exists to detect, and this path avoids
   * creating it (decision by @greluc, 2026-09-15).
   *
   * <p><b>Ordering is load-bearing</b> and is the one REQ-SEC-026 / ADR-0111 established: the
   * database half commits <em>first</em> and the Keycloak user is deleted <em>last</em>. A
   * rolled-back database half then leaves the Keycloak user intact, so the account is simply still
   * there and the request can be retried. The reverse order strands an {@code app_user} row whose
   * Keycloak account is already gone — the very thing that needs a guard to notice.
   *
   * <p>The presence probe is waived on the same terms {@code AccountConsolidationService} waives
   * it: this caller removes the Keycloak user itself, moments after the commit, so the probe would
   * be refusing on account of a user the caller is in the middle of disposing of.
   *
   * @param requestId the pending request to carry out
   * @param grantHistoryErasure whether the admin also grants the Art. 17 wish to anonymise the
   *     surviving handle snapshots; independent of what the member asked for, because the admin
   *     weighs it
   * @param note the admin's recorded reasoning, for the decision record; may be {@code null}
   * @throws EntityNotFoundException when no such pending request exists
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void execute(@NotNull UUID requestId, boolean grantHistoryErasure, @Nullable String note) {
    UUID userId =
        selfProvider.getObject().executeDatabaseHalf(requestId, grantHistoryErasure, note);
    // Only after the database half has committed.
    try {
      keycloakService.deleteUser(userId);
    } catch (RuntimeException e) {
      // The local data is gone, which is what the member asked for. The Keycloak account remaining
      // is visible and resolvable: the roster sync recreates a fresh PENDING registration for it,
      // which an admin can refuse -- unlike the reverse failure, which leaves personal data behind.
      log.warn(
          "Deletion request {} executed locally, but the Keycloak user remains: {}",
          requestId,
          e.toString());
    }
  }

  /**
   * The transactional database half of {@link #execute}: anonymise if granted, then delete.
   *
   * <p>{@code inKeycloak} is flipped to {@code false} before delegating because {@link
   * UserDeletionService} refuses any account the flag still claims is present, and the Keycloak
   * user is still there at this point by design.
   *
   * @param requestId the pending request to carry out
   * @param grantHistoryErasure whether the handle snapshots are anonymised as well
   * @param note the admin's recorded reasoning, or {@code null}
   * @return the id of the deleted account, for the caller's Keycloak half
   * @throws EntityNotFoundException when no such pending request exists
   */
  @Transactional
  public @NotNull UUID executeDatabaseHalf(
      @NotNull UUID requestId, boolean grantHistoryErasure, @Nullable String note) {
    DeletionRequest request = pendingOrThrow(requestId);
    UUID userId = request.getUserId();
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));

    if (grantHistoryErasure) {
      // Before the delete: the id-matched updates only reach rows while the FK still points at the
      // account, and the handle-matched ones need the handle the account still carries.
      handleAnonymisationService.anonymise(userId, user.getEffectiveName());
    }

    auditService.record(
        AuditEventType.ACCOUNT_DELETION_REQUEST_EXECUTED,
        userId,
        user.getEffectiveName(),
        userId,
        AuditDetails.of("requestId", request.getId())
            .with("historyErasureGranted", grantHistoryErasure)
            .with("historyErasureRequested", request.isEraseHistoryRequested())
            .with("noteRecorded", note != null && !note.isBlank()));

    user.setInKeycloak(false);
    userRepository.saveAndFlush(user);
    userDeletionService.deleteUser(
        userId, UserDeletionService.KeycloakPresenceCheck.WAIVED_CALLER_REMOVES_THE_KEYCLOAK_USER);
    // The request row cascades away with the account (V242); there is deliberately no EXECUTED
    // state to read afterwards, because the request is itself personal data about the member.
    return userId;
  }

  /**
   * Loads a request and asserts it is still pending.
   *
   * @param requestId the request
   * @return the pending request
   * @throws EntityNotFoundException when it does not exist or is already decided
   */
  private @NotNull DeletionRequest pendingOrThrow(@NotNull UUID requestId) {
    DeletionRequest request =
        deletionRequestRepository
            .findById(requestId)
            .orElseThrow(
                () -> new EntityNotFoundException("Deletion request not found: " + requestId));
    if (request.getStatus() != DeletionRequestStatus.PENDING) {
      throw new EntityNotFoundException("Deletion request is no longer pending: " + requestId);
    }
    return request;
  }

  /**
   * The member's effective name, for the audit row's subject-label snapshot and for the admin
   * queue, which cannot act on an anonymous request.
   *
   * @param userId the member
   * @return their effective name, or {@code null} when the row is gone
   */
  @Transactional(readOnly = true)
  public @Nullable String handleOf(@NotNull UUID userId) {
    return userRepository.findById(userId).map(User::getEffectiveName).orElse(null);
  }
}
