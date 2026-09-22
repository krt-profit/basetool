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

import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purges registrations that were refused longer ago than the retention window (REQ-SEC-057).
 *
 * <p>A rejection ends the purpose the data was collected for. Deciding on an application is the
 * only thing a {@code REJECTED} row ever served, and once it is decided the account holds an e-mail
 * address, a handle, a Discord snowflake, a guild nickname and — in {@code user_approval_event} —
 * an admin's free-text reason about a natural person who never became a member. Nothing removed any
 * of it: the deletion path in the member list only offers itself for a user already gone from
 * Keycloak, and a rejection deliberately leaves the Keycloak user in place, so the row was
 * unreachable by every deletion affordance the application had. This sweep is that missing path.
 *
 * <p>The window is not zero on purpose. {@link UserRegistrationService#reopenRegistration} exists
 * because approval is fallible (REQ-SEC-034), and purging a rejection destroys the ability to
 * reverse it — so the retention period is also the period in which an erroneous rejection can still
 * be undone. That is the trade the configured {@code max-age} makes.
 *
 * <p><strong>A rejected registration owns nothing, and that is load-bearing.</strong> {@code
 * UserRegistrationService.decide} refuses any transition that does not start at {@code PENDING}, so
 * {@code REJECTED} is reachable only from {@code PENDING} — an account that never held authorities
 * and therefore never created Lager rows, missions or bank postings. The reuse of {@link
 * UserDeletionService} below is nevertheless deliberate: it is the one place that knows the
 * foreign-key order, and routing through it means this sweep cannot drift from it. Should an
 * account somehow arrive here holding data, that service reassigns the shared aggregates instead of
 * destroying them.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RejectedRegistrationRetentionService {

  private final UserRepository userRepository;
  private final UserDeletionService userDeletionService;
  private final KeycloakService keycloakService;

  /**
   * Self-injection (lazy {@link ObjectProvider} to avoid an eager construction cycle) so {@link
   * #purgeRejectedOlderThan} — a non-transactional orchestrator around an external Keycloak write —
   * can invoke its transactional database half {@link #purgeOneTransactionally} through the Spring
   * proxy rather than by self-invocation, which would bypass the {@code @Transactional} advice and
   * silently run the purge with no transaction at all.
   */
  private final ObjectProvider<RejectedRegistrationRetentionService> selfProvider;

  /**
   * Purges every registration rejected before {@code cutoff}, one independent transaction each.
   *
   * <p><strong>Non-transactional orchestrator.</strong> The Keycloak delete is an external
   * side-effect that cannot roll back with a database transaction, so this method holds none
   * ({@link Propagation#NOT_SUPPORTED}) and each row is committed on its own. Two properties follow
   * that a single batch transaction would not have: one unpurgeable row cannot roll back the rows
   * already swept, and a sweep interrupted halfway leaves committed work behind rather than
   * repeating it on the next run.
   *
   * <p>Per row, the database half commits <em>first</em> and the Keycloak user is deleted
   * <em>last</em> — the ordering {@code AccountConsolidationService} established (REQ-SEC-026,
   * ADR-0111) and for the same reason: a rolled-back database half leaves the Keycloak user intact,
   * so the next run re-reads a whole, consistent registration instead of a half-deleted one. The
   * reverse order would strand a row whose account is gone but whose {@code app_user} data — the
   * very data this sweep exists to remove — survives.
   *
   * <p>Failures are per row and never abort the run: an unreachable Keycloak, a stale row or an
   * admin-less realm takes that one registration out of this sweep and leaves it for the next.
   *
   * @param cutoff purge registrations rejected strictly before this instant
   * @return the number of registrations fully purged (database half committed) this run
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public int purgeRejectedOlderThan(@NotNull Instant cutoff) {
    List<UUID> candidates = userRepository.findRejectedDecidedBefore(cutoff);
    if (candidates.isEmpty()) {
      return 0;
    }
    log.info(
        "Retention: purging {} registration(s) rejected before {}...", candidates.size(), cutoff);
    int purged = 0;
    for (UUID userId : candidates) {
      if (purgeOne(userId, cutoff)) {
        purged++;
      }
    }
    log.info("Retention: purged {} of {} rejected registration(s).", purged, candidates.size());
    return purged;
  }

  /**
   * Purges a single rejected registration, swallowing any failure so the surrounding sweep
   * continues with the next candidate.
   *
   * @param userId the rejected registration to purge
   * @param cutoff the retention cutoff, re-checked inside the transaction
   * @return {@code true} when the database half committed, {@code false} when this row was skipped
   */
  private boolean purgeOne(@NotNull UUID userId, @NotNull Instant cutoff) {
    try {
      if (!selfProvider.getObject().purgeOneTransactionally(userId, cutoff)) {
        return false;
      }
    } catch (RuntimeException e) {
      // Log the id only — never the handle or e-mail of the person this row describes.
      log.warn("Retention: could not purge rejected registration {}: {}", userId, e.toString());
      return false;
    }
    // Only after the database half has committed. A throw here leaves a Keycloak user with no
    // app_user row, which the roster sync recreates as a fresh PENDING registration -- visible and
    // resolvable, unlike the reverse failure.
    try {
      keycloakService.deleteUser(userId);
    } catch (RuntimeException e) {
      log.warn(
          "Retention: purged rejected registration {} locally, but its Keycloak user remains: {}",
          userId,
          e.toString());
    }
    return true;
  }

  /**
   * The transactional database half: re-reads the row, re-asserts that it is still a rejection past
   * the cutoff, and hands it to {@link UserDeletionService}.
   *
   * <p>The re-check is what makes the sweep safe against the reopen action. Between the candidate
   * query and this transaction an admin may have reopened a registration (REQ-SEC-034) — which
   * returns it to {@code PENDING} and clears {@code approvedAt} — and purging it then would delete
   * an account an admin is in the middle of reconsidering. Re-reading inside the transaction closes
   * that window instead of narrowing it.
   *
   * <p>{@code inKeycloak} is flipped to {@code false} before delegating because {@link
   * UserDeletionService} refuses any account the flag still claims is present, and a rejection
   * leaves the Keycloak user in place. The live presence probe is waived on the same terms {@code
   * AccountConsolidationService} waives it: this caller removes the Keycloak user itself, moments
   * after this commits, so the probe would be asking about a user the caller is already disposing
   * of.
   *
   * @param userId the rejected registration to purge
   * @param cutoff the retention cutoff to re-assert against the freshly read row
   * @return {@code true} when the row was purged, {@code false} when it no longer qualifies
   */
  @Transactional
  public boolean purgeOneTransactionally(@NotNull UUID userId, @NotNull Instant cutoff) {
    User user = userRepository.findPlainById(userId).orElse(null);
    if (user == null) {
      return false;
    }
    if (user.getApprovalStatus() != ApprovalStatus.REJECTED
        || user.getApprovedAt() == null
        || !user.getApprovedAt().isBefore(cutoff)) {
      log.debug("Retention: skipping {} — no longer a rejection past the cutoff", userId);
      return false;
    }
    user.setInKeycloak(false);
    userRepository.saveAndFlush(user);
    userDeletionService.deleteUser(
        userId, UserDeletionService.KeycloakPresenceCheck.WAIVED_CALLER_REMOVES_THE_KEYCLOAK_USER);
    return true;
  }
}
