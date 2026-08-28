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
 * <p>It pulls the full user list from Keycloak via {@link KeycloakService#fetchUsers} (which pages
 * internally so the set is complete, not just the first server-side page, and resolves roles
 * role-indexed with an incremental Discord back-fill), upserts each user via {@link
 * UserReconciliationService#syncUser}, collects the Keycloak {@code id}s observed this run, and
 * then asks the service to mark every local user NOT in that set as missing — that is how deletions
 * in Keycloak get reflected locally without a hard {@code DELETE}. The completeness of the fetched
 * set is a hard prerequisite (REQ-SEC-043): a truncated list would soft-delete every real member
 * beyond the page cap, which is why {@code fetchUsers} pages and an empty result is treated as
 * "skip" (never a wipe) — including when a role-membership read fails transiently, so a degraded,
 * role-stripped set is never persisted as a successful run.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserSyncService {

  /**
   * How many accounts a single run may soft-delete before the line reporting it escalates from INFO
   * to WARN. Members leave in a trickle; a double-digit batch in one run means something upstream
   * happened to the roster (a bulk deletion, a realm swap, a partially-degraded fetch) and is worth
   * looking at even though the run itself "succeeded".
   */
  private static final int MISSING_USERS_WARN_THRESHOLD = 10;

  private final KeycloakService keycloakService;
  private final UserReconciliationService userReconciliationService;
  private final BankHolderReconciliationService bankHolderReconciliationService;

  /**
   * Fetches the current Keycloak user list and reconciles it into the local table.
   *
   * <p>Failures on individual users are logged and swallowed so a single bad row does not abort the
   * batch. After the loop, {@link UserReconciliationService#markMissingUsers(java.util.Set)} flags
   * every local user whose Keycloak id did not appear in this run. An empty Keycloak fetch is a
   * no-op skip (never a wipe). A batch-level failure (e.g. {@code markMissingUsers} hitting a DB
   * error) propagates to the caller: the scheduled path wraps this in the failure-swallowing {@code
   * TaskMetrics.recordCounting} so the scheduler thread survives; the manual endpoint wraps it in
   * {@code recordCountingRethrow} so the admin sees the failure as an RFC 7807 error rather than a
   * silent success.
   *
   * @return the number of users successfully synced this run (the {@code items} metric); {@code 0}
   *     when Keycloak returned an empty roster
   */
  public int syncFromKeycloak() {
    log.info("Starting scheduled user sync from Keycloak...");
    // Role-indexed + incremental-Discord inputs, both resolved from local state before the fetch:
    // the mappable role names bound the role-membership queries, and the already-linked ids let the
    // fetch skip the per-user federated-identity read for the linked majority (5000-account
    // hardening — see KeycloakService#fetchUsers).
    Set<String> roleNames = userReconciliationService.getMappableRoleNames();
    Set<UUID> knownDiscordLinkedIds = userReconciliationService.getKnownDiscordLinkedUserIds();
    List<KeycloakUserDto> users = keycloakService.fetchUsers(roleNames, knownDiscordLinkedIds);
    if (users.isEmpty()) {
      log.info("No users fetched from Keycloak.");
      return 0;
    }

    int count = 0;
    Set<UUID> keycloakUserIds = new HashSet<>();
    for (KeycloakUserDto user : users) {
      try {
        userReconciliationService.syncUser(user);
        keycloakUserIds.add(user.id());
        count++;
      } catch (Exception e) {
        // Audit finding M-4 (2026-05-20): Keycloak {@code username} can be email-shaped (caught by
        // PiiMasker) or a real-name handle (not caught). Log the JWT-sub UUID instead — sufficient
        // to correlate with the user row on the next sync run, and free of PII.
        log.error("Failed to sync user {}", user.id(), e);
      }
    }
    // Soft-deleting members is the most consequential write of the whole run, and it used to happen
    // between two unrelated lines with the affected-row count discarded — a mass-disappearance
    // (upstream deletion, a realm misconfiguration) left no trace whatsoever. Report it: INFO for
    // the ordinary trickle of leavers, WARN once a single run flags more than
    // MISSING_USERS_WARN_THRESHOLD accounts. Counts only — no handles (REQ-OBS-004).
    int flaggedMissing = userReconciliationService.markMissingUsers(keycloakUserIds);
    if (flaggedMissing > MISSING_USERS_WARN_THRESHOLD) {
      log.warn(
          "User sync flagged {} local users as no longer present in Keycloak in a single run.",
          flaggedMissing);
    } else if (flaggedMissing > 0) {
      log.info(
          "User sync flagged {} local users as no longer present in Keycloak.", flaggedMissing);
    }
    // One aggregate line per run for the role mapping (how many accounts had their roles rewritten,
    // and how many of those landed on the Guest catch-all).
    userReconciliationService.logRoleSyncSummary();
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
