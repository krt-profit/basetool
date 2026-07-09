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

import de.greluc.krt.profit.basetool.backend.model.dto.KeycloakUserDto;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Reconciles the Keycloak user directory into the local {@code app_user} table.
 *
 * <p>The reconciliation is a service (not a task) so it can be driven from BOTH the periodic {@link
 * de.greluc.krt.profit.basetool.backend.task.UserSyncTask} (scheduled, failure-swallowing) AND an
 * admin-triggered manual run via {@code POST /api/v1/users/sync} (request-scoped,
 * failure-surfacing) — both wrapping the same {@link #syncFromKeycloak()} through {@code
 * TaskMetrics} so a manual run is indistinguishable in monitoring and refreshes the same {@code
 * user_sync} last-success gauge.
 *
 * <p>It pulls the full user list from Keycloak via {@link KeycloakService#fetchUsers()} (which
 * pages internally so the set is complete, not just the first server-side page), upserts each user
 * via {@link UserService#syncUser}, collects the Keycloak {@code id}s observed this run, and then
 * asks the service to mark every local user NOT in that set as missing — that is how deletions in
 * Keycloak get reflected locally without a hard {@code DELETE}. The completeness of the fetched set
 * is a hard prerequisite (REQ-SEC-018): a truncated list would soft-delete every real member beyond
 * the page cap, which is why {@code fetchUsers()} pages and an empty result is treated as "skip"
 * (never a wipe).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserSyncService {

  private final KeycloakService keycloakService;
  private final UserService userService;
  private final BankHolderReconciliationService bankHolderReconciliationService;

  /**
   * Fetches the current Keycloak user list and reconciles it into the local table.
   *
   * <p>Failures on individual users are logged and swallowed so a single bad row does not abort the
   * batch. After the loop, {@link UserService#markMissingUsers(java.util.Set)} flags every local
   * user whose Keycloak id did not appear in this run. An empty Keycloak fetch is a no-op skip
   * (never a wipe). A batch-level failure (e.g. {@code markMissingUsers} hitting a DB error)
   * propagates to the caller: the scheduled path wraps this in the failure-swallowing {@code
   * TaskMetrics.recordCounting} so the scheduler thread survives; the manual endpoint wraps it in
   * {@code recordCountingRethrow} so the admin sees the failure as an RFC 7807 error rather than a
   * silent success.
   *
   * @return the number of users successfully synced this run (the {@code items} metric); {@code 0}
   *     when Keycloak returned an empty roster
   */
  public int syncFromKeycloak() {
    log.info("Starting scheduled user sync from Keycloak...");
    List<KeycloakUserDto> users = keycloakService.fetchUsers();
    if (users.isEmpty()) {
      log.info("No users fetched from Keycloak.");
      return 0;
    }

    int count = 0;
    Set<UUID> keycloakUserIds = new HashSet<>();
    for (KeycloakUserDto user : users) {
      try {
        userService.syncUser(user);
        keycloakUserIds.add(user.id());
        count++;
      } catch (Exception e) {
        // Audit finding M-4 (2026-05-20): Keycloak {@code username} can be email-shaped (caught by
        // PiiMasker) or a real-name handle (not caught). Log the JWT-sub UUID instead — sufficient
        // to correlate with the user row on the next sync run, and free of PII.
        log.error("Failed to sync user {}", user.id(), e);
      }
    }
    userService.markMissingUsers(keycloakUserIds);
    log.info("User sync finished. Synced {} users.", count);

    // After the roster is reconciled, keep the bank-holder registry in sync (REQ-BANK-029): every
    // bank-role user becomes an active holder; a role-managed holder whose user lost the role is
    // auto-deactivated. Isolated in its own transaction and swallowed on failure so a bank-side
    // hiccup never aborts the core user sync.
    try {
      bankHolderReconciliationService.reconcileAll();
    } catch (Exception e) {
      log.error("Bank holder reconcile failed; will retry on the next sync run.", e);
    }
    return count;
  }
}
