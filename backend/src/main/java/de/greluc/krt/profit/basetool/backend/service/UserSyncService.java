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

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.dto.KeycloakUserDto;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Reconciles the Keycloak user directory into the local {@code app_user} table, driven by {@link
 * de.greluc.krt.profit.basetool.backend.task.UserSyncTask} and the manual {@code POST
 * /api/v1/users/sync}.
 *
 * <p>Upserts every fetched user via {@link UserReconciliationService#syncUser} and flags local
 * users missing from the roster instead of deleting them. The fetched roster must be complete; an
 * empty or degraded fetch skips the run (REQ-SEC-043).
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

  /** Records {@link MetricNames#USER_SYNC_FAILURES} for per-user reconciliation failures. */
  private final MeterRegistry meterRegistry;

  /**
   * Fetches the Keycloak user list and reconciles it into the local table.
   *
   * <p>A failing user is logged, counted and still treated as present; users absent from the fetch
   * are flagged via {@link UserReconciliationService#markMissingUsers(java.util.Collection)}. An
   * empty fetch skips the run; batch-level failures propagate.
   *
   * @return the number of users synced; {@code 0} when Keycloak returned an empty roster
   */
  public int syncFromKeycloak() {
    log.info("Starting scheduled user sync from Keycloak...");
    Set<String> roleNames = userReconciliationService.getMappableRoleNames();
    Set<UUID> knownDiscordLinkedIds = userReconciliationService.getKnownDiscordLinkedUserIds();
    List<KeycloakUserDto> users = keycloakService.fetchUsers(roleNames, knownDiscordLinkedIds);
    if (users.isEmpty()) {
      log.info("No users fetched from Keycloak.");
      return 0;
    }

    int count = 0;
    int failed = 0;
    Set<UUID> presentInKeycloak = new HashSet<>();
    for (KeycloakUserDto user : users) {
      presentInKeycloak.add(user.id());
      try {
        userReconciliationService.syncUser(user);
        count++;
      } catch (Exception e) {
        failed++;
        meterRegistry.counter(MetricNames.USER_SYNC_FAILURES).increment();
        log.error("Failed to sync user {}", user.id(), e);
      }
    }
    if (failed > 0) {
      log.warn("User sync could not reconcile {} of {} fetched users.", failed, users.size());
    }
    int flaggedMissing = userReconciliationService.markMissingUsers(presentInKeycloak);
    if (flaggedMissing > MISSING_USERS_WARN_THRESHOLD) {
      log.warn(
          "User sync flagged {} local users as no longer present in Keycloak in a single run.",
          flaggedMissing);
    } else if (flaggedMissing > 0) {
      log.info(
          "User sync flagged {} local users as no longer present in Keycloak.", flaggedMissing);
    }
    userReconciliationService.logRoleSyncSummary();
    log.info("User sync finished. Synced {} users.", count);

    try {
      bankHolderReconciliationService.reconcileAll();
    } catch (Exception e) {
      log.error("Bank holder reconcile failed; will retry on the next sync run.", e);
    }
    return count;
  }
}
