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

package de.greluc.krt.profit.basetool.backend.joborder.api;

/**
 * Backend {@code basetool_*} metric names, tag keys and bounded tag values of the job-order module:
 * the integrity check and the open-order queue (REQ-OBS-011). The names and values are part of the
 * dashboards and alert rules under {@code monitoring/}.
 */
public final class JobOrderMetricNames {

  /** Gauge {@code basetool_job_order_integrity_violations} — tag {@code category}. */
  public static final String JOB_ORDER_INTEGRITY_VIOLATIONS =
      "basetool.job.order.integrity.violations";

  /** Gauge {@code basetool_job_order_open_count} — tag {@code status}. */
  public static final String JOB_ORDER_OPEN = "basetool.job.order.open.count";

  /** Gauge {@code basetool_job_order_open_oldest_age_seconds}. */
  public static final String JOB_ORDER_OPEN_OLDEST_AGE = "basetool.job.order.open.oldest.age";

  /**
   * Integrity category: an ordered-item line whose blueprint no longer produces the ordered game
   * item, so its snapshotted materials mirror a foreign recipe (REQ-ORDERS-033).
   */
  public static final String CATEGORY_ITEM_LINE_BLUEPRINT_DRIFT = "item_line_blueprint_drift";

  /** Gauge of orders requiring a valid responsibility transfer, without identifying labels. */
  public static final String JOB_ORDER_INVALID_RESPONSIBILITY =
      "basetool.job.order.invalid.responsibility";

  /** Count of active linked attempts waiting for a Bank decision. */
  public static final String LINKED_MONEY_WAITING = "basetool.job.order.linked.money.waiting.count";

  /** Age of the oldest undecided linked Bank request. */
  public static final String LINKED_MONEY_WAITING_AGE =
      "basetool.job.order.linked.money.waiting.age";

  /** Count of effective real payments whose ledger reversal needs clarification. */
  public static final String LINKED_MONEY_CLARIFICATION =
      "basetool.job.order.linked.money.clarification";

  /** Count of linked attempts with a contradictory order effect. */
  public static final String LINKED_MONEY_CONTRADICTIONS =
      "basetool.job.order.linked.money.contradictions";

  /** Technical failures applying a linked Bank outcome in the order transaction. */
  public static final String LINKED_MONEY_TECHNICAL_FAILURES =
      "basetool.job.order.linked.money.technical.failures";

  private JobOrderMetricNames() {}
}
