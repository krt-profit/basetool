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

package de.greluc.krt.profit.basetool.backend.task;

import de.greluc.krt.profit.basetool.backend.metrics.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import de.greluc.krt.profit.basetool.backend.model.dto.KeycloakUserDto;
import de.greluc.krt.profit.basetool.backend.service.BankHolderReconciliationService;
import de.greluc.krt.profit.basetool.backend.service.KeycloakService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled task that mirrors the Keycloak user directory into the local {@code app_user} table.
 *
 * <p>Runs every {@code app.keycloak.sync.interval} (default {@code PT5M}). Pulls the full user list
 * from Keycloak Admin API via {@link KeycloakService#fetchUsers()} (which pages through {@code
 * first}/{@code max} internally so the set is complete, not just the first server-side page),
 * upserts each user via {@link UserService#syncUser}, collects the set of Keycloak {@code id}s
 * observed in this run, and then asks the service to mark every local user that is NOT in that set
 * as missing — that is how deletions in Keycloak get reflected locally without ever issuing a hard
 * {@code DELETE}. The completeness of the fetched set is a hard prerequisite (REQ-SEC-018): a
 * truncated list would soft-delete every real member beyond the page cap.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserSyncTask {

  private final KeycloakService keycloakService;
  private final UserService userService;
  private final BankHolderReconciliationService bankHolderReconciliationService;
  private final TaskMetrics taskMetrics;

  /**
   * Runs the Keycloak reconciliation through {@link TaskMetrics}, publishing the {@code user_sync}
   * execution counter, duration timer and last-success gauge (the source of the "user sync stale
   * &gt; 15 min" alert). A whole-batch failure is recorded as {@code failure} and swallowed by the
   * wrapper so the scheduler thread survives.
   */
  @Scheduled(fixedDelayString = "${app.keycloak.sync.interval:PT5M}")
  public void syncUsers() {
    taskMetrics.record(ScheduledJob.USER_SYNC, this::synchronizeFromKeycloak);
  }

  /**
   * Fetches the current Keycloak user list and reconciles it into the local table.
   *
   * <p>Failures on individual users are logged and swallowed so a single bad row does not abort the
   * batch. After the loop, {@link UserService#markMissingUsers(java.util.Set)} flags every local
   * user whose Keycloak id did not appear in this run. A batch-level failure (an empty-list guard
   * aside) propagates to {@link TaskMetrics}, which records it as a failed run.
   */
  private void synchronizeFromKeycloak() {
    log.info("Starting scheduled user sync from Keycloak...");
    List<KeycloakUserDto> users = keycloakService.fetchUsers();
    if (users.isEmpty()) {
      log.info("No users fetched from Keycloak.");
      return;
    }

    int count = 0;
    java.util.Set<java.util.UUID> keycloakUserIds = new java.util.HashSet<>();
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
  }
}
