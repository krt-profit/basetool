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

package de.greluc.krt.profit.basetool.backend.metrics;

import org.jetbrains.annotations.NotNull;

/**
 * The closed set of backend {@code @Scheduled} batch jobs that report health through {@link
 * TaskMetrics}.
 *
 * <p>Each constant's {@link #label()} is the bounded value of the {@code job} Prometheus tag on the
 * {@code basetool_scheduled_job_*} meters (REQ-OBS-006 / -011): a fixed, low-cardinality, PII-free
 * enumeration. The high-frequency {@code NotificationStreamService} SSE heartbeat is deliberately
 * excluded — it is a liveness ping, not a batch job with success/failure semantics.
 */
public enum ScheduledJob {

  /** The 5-minute Keycloak directory mirror ({@code UserSyncTask#syncUsers}). */
  USER_SYNC("user_sync"),

  /** The daily read-notification retention purge ({@code NotificationRetentionTask}). */
  NOTIFICATION_RETENTION("notification_retention"),

  /** The hourly default-blueprint back-fill ({@code DefaultBlueprintProvisioningTask}). */
  DEFAULT_BLUEPRINT_PROVISIONING("default_blueprint_provisioning"),

  /** The hourly bank ledger-integrity sweep ({@code BankLedgerIntegrityTask}). */
  BANK_LEDGER_INTEGRITY("bank_ledger_integrity"),

  /** The ~daily UEX commodity/universe sync ({@code UexScheduler}). */
  UEX_SYNC("uex_sync"),

  /** The ~daily Star Citizen wiki sync ({@code ScWikiScheduler}). */
  SCWIKI_SYNC("scwiki_sync"),

  /**
   * The 60-second approval/work-queue depth sampler ({@code BusinessMetricsCollector#refresh}).
   * Wrapped so a wedged sampler surfaces (its {@code last_success} freezes) rather than silently
   * feeding every {@code *ApprovalOverdue} alert stale queue gauges.
   */
  BUSINESS_METRICS("business_metrics");

  private final String label;

  /**
   * Binds the constant to its bounded {@code job} tag value.
   *
   * @param label the fixed, snake-case Prometheus tag value for this job
   */
  ScheduledJob(@NotNull String label) {
    this.label = label;
  }

  /**
   * The bounded {@code job} tag value for this job on the {@code basetool_scheduled_job_*} meters.
   *
   * @return the fixed, snake-case, PII-free tag value
   */
  public @NotNull String label() {
    return label;
  }
}
