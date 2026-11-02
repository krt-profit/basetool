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

package de.greluc.krt.profit.basetool.backend.privacy.api;

/**
 * Backend {@code basetool_*} metric names, tag keys and bounded tag values of the privacy module:
 * account deletion (REQ-OBS-011). The names and values are part of the dashboards and alert rules
 * under {@code monitoring/}.
 */
public final class PrivacyMetricNames {

  /**
   * Counter {@code basetool_account_deletion_keycloak_failures_total} (untagged). Bumped when an
   * erasure's local half has committed but the Keycloak user could not be deleted (REQ-SEC-061).
   * Normally zero; any increment needs manual deletion in Keycloak (REQ-OBS-011).
   */
  public static final String ACCOUNT_DELETION_KEYCLOAK_FAILURES =
      "basetool.account.deletion.keycloak.failures";

  /**
   * Gauge {@code basetool_deletion_request_pending_count} — members' Art. 17 erasure requests
   * awaiting an admin decision (REQ-SEC-061).
   */
  public static final String DELETION_REQUEST_PENDING = "basetool.deletion.request.pending.count";

  /**
   * Gauge {@code basetool_deletion_request_pending_oldest_age_seconds} — how long the
   * longest-waiting erasure request has waited (REQ-SEC-061). Its alert threshold sits well inside
   * the one-month statutory deadline of GDPR Art. 12(3).
   */
  public static final String DELETION_REQUEST_PENDING_OLDEST_AGE =
      "basetool.deletion.request.pending.oldest.age";

  private PrivacyMetricNames() {}
}
