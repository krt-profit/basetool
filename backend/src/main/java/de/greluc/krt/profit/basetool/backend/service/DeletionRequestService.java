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
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
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
 * The members' Art. 17 erasure requests and the admin decisions on them (REQ-SEC-061, ADR-0181).
 *
 * <p>A member raises a request on their own profile and an admin decides it; nothing here deletes
 * an account as a side effect of the member's action.
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

  /** How many times {@link #raise} retries after losing a concurrent insert. */
  private static final int RAISE_ATTEMPTS = 3;

  /**
   * Raises a member's erasure request, idempotently: a member with a pending request gets it back.
   *
   * <p>Each attempt runs in a fresh {@code REQUIRES_NEW} transaction, so a lost concurrent insert
   * can be recovered by re-reading the winner's row. A race that outlives the retry bound
   * propagates as {@code DataIntegrityViolationException} (409).
   *
   * @param userId the member asking to be erased
   * @param eraseHistoryRequested whether they also ask for their handle snapshots to be anonymised,
   *     which an admin decides
   * @return the member's open request, newly created or pre-existing
   */
  public @NotNull DeletionRequest raise(@NotNull UUID userId, boolean eraseHistoryRequested) {
    DataIntegrityViolationException last = null;
    for (int attempt = 1; attempt <= RAISE_ATTEMPTS; attempt++) {
      try {
        return selfProvider.getObject().raiseWithinNewTransaction(userId, eraseHistoryRequested);
      } catch (DataIntegrityViolationException e) {
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
   * One find-or-create attempt of {@link #raise}, in a transaction of its own. Public only so the
   * self-proxy can reach it.
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
        null,
        userId,
        AuditDetails.of("eraseHistoryRequested", eraseHistoryRequested));

    eventPublisher.publishEvent(new AccountDeletionRequestedEvent(userId, handleOf(userId)));
    log.info("Member {} raised an account-deletion request", userId);
    return saved;
  }

  /**
   * Withdraws a member's own pending request, keeping the row as {@link
   * DeletionRequestStatus#WITHDRAWN}.
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
        null,
        userId,
        AuditDetails.of("requestId", request.getId()));

    eventPublisher.publishEvent(new AccountDeletionRequestResolvedEvent(userId));
    log.info("Member {} withdrew their account-deletion request", userId);
    return Optional.of(request);
  }

  /**
   * Refuses a request with the admin's mandatory reasoning (Art. 12(4)). The note is stored on the
   * request row, never in the audit details.
   *
   * @param requestId the request to refuse
   * @param note the admin's reasoning; must not be blank
   * @return the refused request
   * @throws NotFoundException when no such pending request exists
   * @throws IllegalArgumentException when the note is blank
   */
  @Transactional
  public @NotNull DeletionRequest decline(
      @NotNull UUID requestId, @Nullable String note, @Nullable Long clientVersion) {
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
   * Returns the member's most recent request in any status, as shown on their profile page.
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
   * Carries a request out: optionally anonymises the handle snapshots, then deletes the account,
   * the local row first and the Keycloak user last.
   *
   * <p>The database half commits before the Keycloak delete, so a rollback leaves the account
   * intact and the request retryable (ADR-0111).
   *
   * @param requestId the pending request to carry out
   * @param grantHistoryErasure whether the admin grants anonymising the surviving handle snapshots,
   *     independent of what the member asked for
   * @throws NotFoundException when no such pending request exists
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void execute(
      @NotNull UUID requestId, boolean grantHistoryErasure, @Nullable Long clientVersion) {
    UUID userId =
        selfProvider.getObject().executeDatabaseHalf(requestId, grantHistoryErasure, clientVersion);
    try {
      keycloakService.deleteUser(userId);
    } catch (RuntimeException e) {
      selfProvider.getObject().recordKeycloakDeleteFailure(requestId, userId, e);
      log.warn(
          "Deletion request {} executed locally, but the Keycloak user remains: {}",
          requestId,
          e.toString());
    }
  }

  /**
   * Records a half-finished erasure whose local half committed but whose Keycloak delete failed, in
   * a transaction of its own.
   *
   * <p>The audit row uses the deleted account's id as subject and a {@code null} target; only the
   * exception's class name goes into the payload.
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
   * @throws NotFoundException when no such pending request exists
   */
  @Transactional
  public @NotNull UUID executeDatabaseHalf(
      @NotNull UUID requestId, boolean grantHistoryErasure, @Nullable Long clientVersion) {
    DeletionRequest request = pendingOrThrow(requestId, clientVersion);
    UUID userId = request.getUserId();
    User user =
        Entities.require(userRepository.findById(userId), () -> "User not found: " + userId);

    if (grantHistoryErasure) {
      handleAnonymisationService.anonymise(
          userId, HandleSpellings.of(user).filter(Objects::nonNull).toList());
    }

    auditService.record(
        AuditEventType.ACCOUNT_DELETION_REQUEST_EXECUTED,
        userId,
        null,
        userId,
        AuditDetails.of("requestId", request.getId())
            .with("historyErasureGranted", grantHistoryErasure)
            .with("historyErasureRequested", request.isEraseHistoryRequested()));

    eventPublisher.publishEvent(new AccountDeletionRequestResolvedEvent(userId));

    user.setInKeycloak(false);
    userRepository.saveAndFlush(user);
    userDeletionService.deleteUser(
        userId, UserDeletionService.KeycloakPresenceCheck.WAIVED_CALLER_REMOVES_THE_KEYCLOAK_USER);
    return userId;
  }

  /**
   * Loads a request for a decision under a pessimistic row lock and asserts it is still pending.
   *
   * <p>The lock serialises execution against a concurrent withdrawal, which optimistic locking
   * cannot catch because execution never updates this row. The client's version is checked on top.
   *
   * @param requestId the request
   * @param clientVersion the version the client last saw, or {@code null} to skip the check
   * @return the pending request, locked for the rest of the transaction
   * @throws NotFoundException when it does not exist or is already decided
   */
  private @NotNull DeletionRequest pendingOrThrow(
      @NotNull UUID requestId, @Nullable Long clientVersion) {
    DeletionRequest request =
        Entities.require(
            deletionRequestRepository.findByIdForDecision(requestId),
            () -> "Deletion request not found: " + requestId);
    if (request.getStatus() != DeletionRequestStatus.PENDING) {
      throw new NotFoundException("Deletion request is no longer pending: " + requestId);
    }
    OptimisticLock.checkOptionalClient(
        request.getVersion(), clientVersion, DeletionRequest.class, requestId);
    return request;
  }

  /**
   * Returns the member's effective name for the admin queue.
   *
   * @param userId the member
   * @return their effective name, or {@code null} when the row is gone
   */
  @Transactional(readOnly = true)
  public @Nullable String handleOf(@NotNull UUID userId) {
    return userRepository.findPlainById(userId).map(User::getEffectiveName).orElse(null);
  }

  /**
   * Returns the effective names of several members in one query.
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
