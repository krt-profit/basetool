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
import de.greluc.krt.profit.basetool.backend.event.AccountDeletionRequestResolvedEvent;
import de.greluc.krt.profit.basetool.backend.event.AccountDeletionRequestedEvent;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.DeletionRequest;
import de.greluc.krt.profit.basetool.backend.model.DeletionRequestStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.DeletionRequestRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.HandleSpellings;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityNotFoundException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * an account as a side effect of the member's click</b> — that is the whole design: decision 5,
 * {@literal @}greluc, ADR-0181. The deletion removes the Keycloak account, purges the member's
 * warehouse stock and hangar and reassigns their missions and refinery orders (REQ-DATA-008); none
 * of it is reversible, and a mis-click on one's own profile page must not be able to start it.
 *
 * <p>Modelled on the registration-approval queue rather than a second pattern: a row per request, a
 * status, a decider, a decision instant and a recorded reason.
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
  private final MeterRegistry meterRegistry;

  /**
   * Self-injection (lazy {@link ObjectProvider} to avoid an eager construction cycle) so {@link
   * #execute} can call its own {@code @Transactional} database half through the proxy. A direct
   * {@code this.} call would bypass the transaction advice entirely, which is the bug this pattern
   * exists to prevent.
   */
  private final ObjectProvider<DeletionRequestService> selfProvider;

  /**
   * How many times {@link #raise} retries a concurrent-insert loss.
   *
   * <p>Three, matching the other find-or-create sites. The loser only has to lose once for the
   * winner's row to be committed and readable, so two would do; the third is there for the case
   * where the winner's request is withdrawn between the failed insert and the retry's pre-read,
   * which puts the loser back at the start legitimately.
   */
  private static final int RAISE_ATTEMPTS = 3;

  /**
   * Raises a member's erasure request.
   *
   * <p>Idempotent by design rather than by check-then-act: a partial unique index on {@code
   * (user_id) WHERE status = 'PENDING'} is what guarantees one open request per member, so a member
   * who double-clicks gets their existing request back instead of a second queue entry.
   *
   * <p><b>A non-transactional orchestrator around a {@code REQUIRES_NEW} attempt, and that shape is
   * the whole point.</b> The recovery used to sit in a {@code catch} inside the same transaction as
   * the failing {@code saveAndFlush}, which cannot work: JPA marks a transaction rollback-only once
   * a flush has failed, and Postgres aborts the backend transaction on the constraint violation
   * (SQLSTATE 25P02), so the recovery {@code SELECT} on that connection fails outright and the
   * commit hook throws {@code UnexpectedRollbackException}. The member's second click answered 500.
   * Retrying in a <em>fresh</em> transaction is what makes the recovery reachable — by then the
   * winner has committed and the pre-read finds their row. Same pattern, same reason, as {@code
   * OperationService#setPayoutStatus} and {@code MaterialClaimService#upsertClaim}
   * (backend/CLAUDE.md, "find-or-create races").
   *
   * <p>A race that outlives the attempt bound is allowed to propagate rather than be swallowed:
   * {@code DataIntegrityViolationException} maps to a truthful 409, and a silent success would be
   * worse than a status the client can retry on.
   *
   * @param userId the member asking to be erased
   * @param eraseHistoryRequested whether they also ask for the surviving handle snapshots to be
   *     anonymised — a wish an admin decides deliberately, never an instruction
   * @return the member's open request, newly created or pre-existing
   */
  public @NotNull DeletionRequest raise(@NotNull UUID userId, boolean eraseHistoryRequested) {
    DataIntegrityViolationException last = null;
    for (int attempt = 1; attempt <= RAISE_ATTEMPTS; attempt++) {
      try {
        return selfProvider.getObject().raiseWithinNewTransaction(userId, eraseHistoryRequested);
      } catch (DataIntegrityViolationException e) {
        // The partial unique index fired: a concurrent click won. Its row is the answer, and the
        // next attempt's pre-read will find it now that this transaction is gone.
        last = e;
        log.debug(
            "Concurrent deletion request for {} (attempt {} of {}); retrying to read the winner's"
                + " row",
            userId,
            attempt,
            RAISE_ATTEMPTS);
      }
    }
    throw last;
  }

  /**
   * One attempt at the find-or-create, in a transaction of its own.
   *
   * <p>{@code REQUIRES_NEW} rather than the default, because the caller retries on failure and a
   * joined transaction would hand the retry the same poisoned one. Public only so the self-proxy
   * can reach it; {@link #raise} is the entry point.
   *
   * @param userId the member asking to be erased
   * @param eraseHistoryRequested the member's wish about the surviving handle snapshots
   * @return the request this attempt found or created
   * @throws DataIntegrityViolationException when a concurrent attempt committed first
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public @NotNull DeletionRequest raiseWithinNewTransaction(
      @NotNull UUID userId, boolean eraseHistoryRequested) {
    Optional<DeletionRequest> existing =
        deletionRequestRepository.findByUserIdAndStatus(userId, DeletionRequestStatus.PENDING);
    if (existing.isPresent()) {
      return existing.get();
    }
    final DeletionRequest saved =
        deletionRequestRepository.saveAndFlush(new DeletionRequest(userId, eraseHistoryRequested));

    auditService.record(
        AuditEventType.ACCOUNT_DELETION_REQUESTED,
        userId,
        // No subject label. REQ-AUDIT-001 limits it to a non-personal display label, and this
        // one is a person by definition -- who the row is about is in actor_user_id and
        // target_user_id, which the viewer resolves against the live roster and the erasure
        // reaches. A name here outlived the erasure for the full 24-month retention:
        // anonymise() rewrites actor_handle, and subject_label sat on the same row intact.
        null,
        userId,
        AuditDetails.of("eraseHistoryRequested", eraseHistoryRequested));

    // Published inside the transaction on purpose: the listener is AFTER_COMMIT, so the admins'
    // notification cannot describe a request that then rolls back (REQ-NOTIF-002). Publishing from
    // the non-transactional orchestrator instead would leave no transaction for it to bind to and
    // the event would never be delivered.
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
        // See the note in raise(): the subject is identified by id, never by name.
        null,
        userId,
        AuditDetails.of("requestId", request.getId()));

    // Clears the administrators' "member requests erasure" items, which carry the member's handle
    // in their render parameters (REQ-NOTIF-018). Withdrawal used to publish nothing, so the
    // request kept showing in every admin's bell after the member took it back.
    eventPublisher.publishEvent(new AccountDeletionRequestResolvedEvent(userId));
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
  public @NotNull DeletionRequest decline(
      @NotNull UUID requestId, @Nullable String note, @Nullable Long clientVersion) {
    // A refusal is the one decision whose reasoning survives -- the row stays, in DECLINED, and
    // the member reads the reason on their profile page. An execution has nowhere to put one: the
    // row cascades away with the account it was about, and REQ-AUDIT-001 keeps free text out of
    // the audit payload. So execute() takes no note at all rather than accepting one and dropping
    // it, which is what it used to do.
    if (note == null || note.isBlank()) {
      throw new IllegalArgumentException("A declined deletion request must carry a reason");
    }
    DeletionRequest request = pendingOrThrow(requestId, clientVersion);
    request.setStatus(DeletionRequestStatus.DECLINED);
    request.setDecidedAt(Instant.now());
    request.setDecidedById(authHelperService.currentUserId().orElse(null));
    request.setDecisionNote(note);
    deletionRequestRepository.save(request);

    auditService.record(
        AuditEventType.ACCOUNT_DELETION_REQUEST_DECLINED,
        request.getUserId(),
        // See the note in raise(): the subject is identified by id, never by name.
        null,
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
   * creating it (decision by {@literal @}greluc, 2026-09-15).
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
   * @throws EntityNotFoundException when no such pending request exists
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void execute(
      @NotNull UUID requestId, boolean grantHistoryErasure, @Nullable Long clientVersion) {
    UUID userId =
        selfProvider.getObject().executeDatabaseHalf(requestId, grantHistoryErasure, clientVersion);
    // Only after the database half has committed.
    try {
      keycloakService.deleteUser(userId);
    } catch (RuntimeException e) {
      // The local data is gone, which is what the member asked for -- unlike the reverse failure,
      // this one leaves no personal data behind. What it does leave is a Keycloak account that can
      // still log in, and it needs a human in the Keycloak console. It cannot be found by the
      // REQ-SEC-059 orphan gauge: that counts a local row whose Keycloak account has gone, the
      // opposite direction, and this account has no local row left at all.
      //
      // Nor is the recreated row a reliable backstop. On the next login or roster sync the
      // reconciliation inserts a fresh one, PENDING and refusable for an ordinary member -- but
      // UserRegistrationService#stampNewPendingRegistration carves ADMIN-realm-role holders out
      // for bootstrap safety, so an admin's row lands on the ACTIVE entity default with full
      // authority. So this path leaves a counter an alert watches and an audit row that outlives
      // the request, rather than a log line nothing reads.
      selfProvider.getObject().recordKeycloakDeleteFailure(requestId, userId, e);
      log.warn(
          "Deletion request {} executed locally, but the Keycloak user remains: {}",
          requestId,
          e.toString());
    }
  }

  /**
   * Records a half-finished erasure: the local half committed, the Keycloak delete did not.
   *
   * <p>{@code REQUIRES_NEW} rather than the caller's transaction, because {@link #execute} is
   * {@code NOT_SUPPORTED} by design and has no transaction at this point — the database half has
   * already committed, which is the only reason the Keycloak delete was attempted at all. {@link
   * AuditService#record} is {@code MANDATORY}, so without a transaction of its own this would throw
   * inside a catch block and replace one swallowed failure with another.
   *
   * <p>The audit row carries the deleted account's id as its <b>subject</b> and a {@code null}
   * target: {@code target_user_id} is a foreign key to an {@code app_user} row that no longer
   * exists. Only the exception's class name goes into the payload — its message can echo Keycloak's
   * own description of the account, and the details payload takes no free text (REQ-AUDIT-001). The
   * full message is in the log line beside this call.
   *
   * @param requestId the request that was carried out
   * @param userId the account whose Keycloak user survived
   * @param failure what the Keycloak delete threw
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordKeycloakDeleteFailure(
      @NotNull UUID requestId, @NotNull UUID userId, @NotNull RuntimeException failure) {
    meterRegistry.counter(MetricNames.ACCOUNT_DELETION_KEYCLOAK_FAILURES).increment();
    auditService.record(
        AuditEventType.ACCOUNT_DELETION_KEYCLOAK_DELETE_FAILED,
        userId,
        // See the note in raise(): the subject is identified by id, never by name.
        null,
        null,
        AuditDetails.of("requestId", requestId).with("error", failure.getClass().getSimpleName()));
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
   * @return the id of the deleted account, for the caller's Keycloak half
   * @throws EntityNotFoundException when no such pending request exists
   */
  @Transactional
  public @NotNull UUID executeDatabaseHalf(
      @NotNull UUID requestId, boolean grantHistoryErasure, @Nullable Long clientVersion) {
    DeletionRequest request = pendingOrThrow(requestId, clientVersion);
    UUID userId = request.getUserId();
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));

    if (grantHistoryErasure) {
      // Before the delete: the id-matched updates only reach rows while the FK still points at the
      // account, and the text-matched ones need the names the account still carries.
      //
      // Every spelling, not the effective name alone. A handover or a job-order contact is typed
      // by hand, and whoever typed it wrote what they call the person -- as likely the Discord
      // nickname as the display name. Passing one spelling left the others standing.
      //
      // The list comes from HandleSpellings so this and the Art. 15 export cannot disagree about
      // what a member's names are; HandleSpellingCoverageTest holds it against the person-search
      // registry, which is itself swept against information_schema.
      handleAnonymisationService.anonymise(
          userId, HandleSpellings.of(user).filter(Objects::nonNull).toList());
    }

    auditService.record(
        AuditEventType.ACCOUNT_DELETION_REQUEST_EXECUTED,
        userId,
        // See the note in raise(). This was the worst of the four: on a granted erasure the name
        // went back in six lines after being removed, into a row the erasure had just rewritten.
        null,
        userId,
        AuditDetails.of("requestId", request.getId())
            .with("historyErasureGranted", grantHistoryErasure)
            .with("historyErasureRequested", request.isEraseHistoryRequested()));

    // Same clearing as withdraw and decline, and on this path it is also the erasure: the request
    // notification names the member, one row per administrator, and UserDeletionService removes
    // notifications by RECIPIENT -- which these are not. Superseding them here means the name is
    // gone when the account is, on every path, rather than only when the history checkbox was
    // ticked and granted.
    eventPublisher.publishEvent(new AccountDeletionRequestResolvedEvent(userId));

    user.setInKeycloak(false);
    userRepository.saveAndFlush(user);
    userDeletionService.deleteUser(
        userId, UserDeletionService.KeycloakPresenceCheck.WAIVED_CALLER_REMOVES_THE_KEYCLOAK_USER);
    // The request row cascades away with the account (V242); there is deliberately no EXECUTED
    // state to read afterwards, because the request is itself personal data about the member.
    return userId;
  }

  /**
   * Loads a request for a decision, row-locked, and asserts it is still pending.
   *
   * <p><b>The lock is pessimistic because the execute path never writes this row.</b>
   * {@code @Version} protects {@link #decline} and {@link #withdraw} for free, since they save the
   * entity — but the execution audits, writes {@code app_user}, deletes the user and lets the
   * {@code ON DELETE CASCADE} take the request, so Hibernate issues no versioned {@code UPDATE} and
   * optimistic locking has nothing to compare. Without the lock both transactions read {@code
   * PENDING}, the member's withdrawal commits first, and the execution's cascade deletes the
   * just-withdrawn row along with the account: the member believes they took their request back and
   * is irreversibly deleted anyway.
   *
   * <p>The client's version is checked on top of the lock, so an admin deciding from a queue page
   * that has gone stale gets a 409 rather than acting on a request whose current state they cannot
   * see.
   *
   * @param requestId the request
   * @param clientVersion the version the client last saw, or {@code null} to decide whatever is
   *     there — the admin force-save semantics {@code OptimisticLock#checkOptionalClient} exists
   *     for
   * @return the pending request, locked for the rest of the transaction
   * @throws EntityNotFoundException when it does not exist or is already decided
   */
  private @NotNull DeletionRequest pendingOrThrow(
      @NotNull UUID requestId, @Nullable Long clientVersion) {
    DeletionRequest request =
        deletionRequestRepository
            .findByIdForDecision(requestId)
            .orElseThrow(
                () -> new EntityNotFoundException("Deletion request not found: " + requestId));
    if (request.getStatus() != DeletionRequestStatus.PENDING) {
      throw new EntityNotFoundException("Deletion request is no longer pending: " + requestId);
    }
    OptimisticLock.checkOptionalClient(
        request.getVersion(), clientVersion, DeletionRequest.class, requestId);
    return request;
  }

  /**
   * The member's effective name, for the admin queue, which cannot act on an anonymous request.
   *
   * <p>Read by the queue projection only. It used to feed the audit rows' subject label as well,
   * which is how a granted erasure came to leave the name in a row it had just rewritten; the label
   * is {@code null} on all four events now (REQ-AUDIT-001).
   *
   * @param userId the member
   * @return their effective name, or {@code null} when the row is gone
   */
  @Transactional(readOnly = true)
  public @Nullable String handleOf(@NotNull UUID userId) {
    return userRepository.findPlainById(userId).map(User::getEffectiveName).orElse(null);
  }

  /**
   * The effective names of several members, in one query.
   *
   * <p>The admin queue rendered one {@link #handleOf(UUID)} per row, which is the N+1 REQ-DATA-003
   * forbids: a queue of twenty requests issued twenty-one statements. A month's worth of Art. 12(3)
   * deadlines is exactly when that page is opened repeatedly.
   *
   * <p>A missing member is absent from the map rather than mapped to {@code null}, so the caller's
   * {@code get} keeps the same "no handle" answer {@code handleOf} gives — the row survives its
   * subject in {@code WITHDRAWN} and {@code DECLINED}, so absence is a normal state and not an
   * error.
   *
   * @param userIds the members to look up; an empty collection queries nothing
   * @return effective name by member id, without entries for ids that no longer exist
   */
  @Transactional(readOnly = true)
  public @NotNull Map<UUID, String> handlesOf(@NotNull Collection<UUID> userIds) {
    if (userIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, String> handles = new HashMap<>();
    for (User user : userRepository.findAllById(userIds)) {
      String name = user.getEffectiveName();
      if (name != null) {
        handles.put(user.getId(), name);
      }
    }
    return handles;
  }
}
