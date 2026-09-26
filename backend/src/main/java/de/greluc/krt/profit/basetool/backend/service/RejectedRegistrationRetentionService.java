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
 * Purges registrations rejected longer ago than the configured retention window (REQ-SEC-057).
 *
 * <p>Within the window a rejection can still be reopened (REQ-SEC-034). Deletion goes through
 * {@link UserDeletionService}, which owns the foreign-key order.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RejectedRegistrationRetentionService {

  private final UserRepository userRepository;
  private final UserDeletionService userDeletionService;
  private final KeycloakService keycloakService;

  /**
   * Lazy self-reference so {@link #purgeRejectedOlderThan} calls {@link #purgeOneTransactionally}
   * through the Spring proxy and its {@code @Transactional} advice applies.
   */
  private final ObjectProvider<RejectedRegistrationRetentionService> selfProvider;

  /**
   * Purges every registration rejected before {@code cutoff}, each in its own transaction.
   *
   * <p>Runs without a transaction; per row the database half commits first and the Keycloak user is
   * deleted last (ADR-0111). A failing row is skipped and left for the next run.
   *
   * @param cutoff purge registrations rejected strictly before this instant
   * @return the number of registrations whose database half committed this run
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
      log.warn("Retention: could not purge rejected registration {}: {}", userId, e.toString());
      return false;
    }
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
   * Transactional database half of a purge: re-reads the row, re-checks that it is still a
   * rejection past the cutoff, and deletes it via {@link UserDeletionService}.
   *
   * <p>The re-check skips a registration reopened meanwhile. {@code inKeycloak} is cleared first
   * because the caller deletes the Keycloak user itself after this commits.
   *
   * @param userId the rejected registration to purge
   * @param cutoff the retention cutoff to re-check against the freshly read row
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
