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

package de.greluc.krt.profit.basetool.backend.privacy.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.identity.api.ErasedAccountRegister;
import de.greluc.krt.profit.basetool.backend.identity.api.KeycloakService;
import de.greluc.krt.profit.basetool.backend.platform.api.ClientDirectory;
import de.greluc.krt.profit.basetool.backend.platform.api.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.platform.api.TaskMetrics;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps every deletion of the erasure register in force (REQ-DATA-048): deletes an account row a
 * restore or a re-created account brought back, deletes a Keycloak user a failed delete or a
 * restore left behind, and drops the entries older than {@code app.privacy.erasure-register
 * .max-age}. Runs at startup and then every {@code interval}; one {@link
 * AuditEventType#ERASURE_REGISTER_APPLIED} records the counts when anything changed.
 */
@Component
@ConditionalOnProperty(
    prefix = "app.privacy.erasure-register",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
public class ErasureRegisterTask {

  private final ErasedAccountRegister register;
  private final ErasureReapplication reapplication;
  private final KeycloakService keycloakService;
  private final ClientDirectory clientDirectory;
  private final ErasureRegisterProperties properties;
  private final AuditRecorder auditRecorder;
  private final TransactionTemplate transactionTemplate;
  private final TaskMetrics taskMetrics;

  /**
   * Runs one pass, publishing the {@code erasure_register} job metrics; a failure is recorded and
   * swallowed by {@link TaskMetrics}.
   */
  @Scheduled(fixedDelayString = "${app.privacy.erasure-register.interval:PT24H}")
  public void keepErasuresInForce() {
    taskMetrics.recordCounting(ScheduledJob.ERASURE_REGISTER, () -> sweep(Instant.now()));
  }

  /**
   * Drops the expired entries, re-applies every registered deletion and records the audit row;
   * fails the run when a deletion could not be re-applied, after the others were tried.
   *
   * @param now the time the run starts; entries older than {@code maxAge} before it expire
   * @return the number of entries dropped plus account rows and Keycloak users deleted
   * @throws IllegalStateException when at least one deletion could not be re-applied
   */
  int sweep(@NotNull Instant now) {
    Instant cutoff = now.minus(properties.maxAge());
    int expired = register.expireBefore(cutoff);
    int accountRows = 0;
    int keycloakUsers = 0;
    int failed = 0;
    for (UUID userId : register.erasedAccounts()) {
      try {
        if (reapplication.deleteAccountRow(userId)) {
          accountRows++;
        }
        if (deleteKeycloakUser(userId)) {
          keycloakUsers++;
        }
      } catch (RuntimeException e) {
        log.warn("Re-applying the deletion of account {} failed", userId, e);
        failed++;
      }
    }
    int changed = expired + accountRows + keycloakUsers;
    if (changed > 0) {
      AuditDetails details =
          AuditDetails.of("accountRows", accountRows)
              .with("keycloakUsers", keycloakUsers)
              .with("expired", expired)
              .with("cutoff", cutoff);
      transactionTemplate.executeWithoutResult(_ ->
          auditRecorder.record(AuditEventType.ERASURE_REGISTER_APPLIED, null, null, null, details));
    }
    log.info(
        "Erasure register: {} account row(s) and {} Keycloak user(s) deleted again, {} entry(ies)"
            + " expired before {}.",
        accountRows,
        keycloakUsers,
        expired,
        cutoff);
    if (failed > 0) {
      throw new IllegalStateException(failed + " registered deletion(s) could not be re-applied");
    }
    return changed;
  }

  /**
   * Deletes the Keycloak user of a registered account when one exists, never the service account of
   * a configured gateway.
   *
   * @param userId the registered id
   * @return {@code true} when a Keycloak user was deleted
   */
  private boolean deleteKeycloakUser(@NotNull UUID userId) {
    Optional<String> username = keycloakService.usernameOf(userId);
    if (username.isEmpty() || clientDirectory.isGatewayServiceAccount(username.get())) {
      return false;
    }
    keycloakService.deleteUser(userId);
    return true;
  }

  /**
   * Publishes {@code basetool_scheduled_job_enabled{task="erasure_register"} = 1}, so {@code
   * ScheduledJobStale} can tell a disabled job from one that never succeeded.
   */
  @PostConstruct
  void publishEnabledGauge() {
    taskMetrics.markEnabled(ScheduledJob.ERASURE_REGISTER);
  }
}
