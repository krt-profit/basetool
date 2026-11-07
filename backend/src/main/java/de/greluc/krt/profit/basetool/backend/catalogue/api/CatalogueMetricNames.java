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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;

/**
 * Backend {@code basetool_*} metric names, tag keys and bounded tag values of the catalogue sync
 * and import (REQ-OBS-011). The names and values are part of the dashboards and alert rules under
 * {@code monitoring/}.
 */
public final class CatalogueMetricNames {

  /**
   * Counter {@code basetool_catalogue_orphan_sweep_skipped_total} — tags {@code sweep} ({@link
   * #SWEEP_ITEM}) and {@code reason} ({@link #SWEEP_SKIP_INCOMPLETE} / {@link
   * #SWEEP_SKIP_NO_ROWS}), one per stood-down sweep.
   */
  public static final String CATALOGUE_ORPHAN_SWEEP_SKIPPED =
      "basetool.catalogue.orphan.sweep.skipped";

  /**
   * Untagged counter of items whose SC Wiki {@code class} or P4K {@code itemClass} is no {@link
   * ShipComponentClass} (REQ-OBS-023).
   */
  public static final String CATALOGUE_ITEM_CLASS_UNKNOWN = "basetool.catalogue.item.class.unknown";

  /**
   * Untagged counter of matched items whose raw P4K grade lies outside 1 to 4 (REQ-OBS-023), one
   * per item and APPLY.
   */
  public static final String CATALOGUE_ITEM_GRADE_UNKNOWN = "basetool.catalogue.item.grade.unknown";

  /**
   * Gauge {@code basetool_catalogue_items_with_stats} — tag {@code source} ({@link
   * GameItemStatSource#name()}): the game items with a stored blueprint stat base value per source
   * (REQ-OBS-023).
   */
  public static final String CATALOGUE_ITEMS_WITH_STATS = "basetool.catalogue.items.with.stats";

  /** Counter {@code basetool_sync_events_total} — tags {@code source}, {@code event_type}. */
  public static final String SYNC_EVENTS = "basetool.sync.events";

  /**
   * Counter {@code basetool_external_fetch_errors_total} — tags {@code source} ({@link #SOURCE_UEX}
   * / {@link #SOURCE_SCWIKI}) and {@code reason} ({@link #REASON_FETCH} / {@link #REASON_CENSUS}).
   * Incremented for upstream fetch/parse errors and incomplete page walks (REQ-OBS-011).
   */
  public static final String EXTERNAL_FETCH_ERRORS = "basetool.external.fetch.errors";

  /** Gauge {@code basetool_p4k_import_job_pending_count} — tag {@code status}. */
  public static final String P4K_IMPORT_JOB_PENDING = "basetool.p4k.import.job.pending.count";

  /** Gauge {@code basetool_p4k_import_job_pending_oldest_age_seconds}. */
  public static final String P4K_IMPORT_JOB_PENDING_OLDEST_AGE =
      "basetool.p4k.import.job.pending.oldest.age";

  /**
   * Counter {@code basetool_p4k_import_jobs_total} — tags {@code outcome} ({@link
   * MetricNames#OUTCOME_SUCCEEDED} / {@link MetricNames#OUTCOME_FAILED}) and {@code kind} ({@link
   * MetricNames#TAG_KIND}). Bumped at each terminal transition so a reliably-failing import
   * surfaces as failures rather than an innocuous-looking empty pending queue.
   */
  public static final String P4K_IMPORT_JOBS = "basetool.p4k.import.jobs";

  /**
   * Counter {@code basetool_p4k_import_rejected_total} — tag {@code reason}, the bounded {@link
   * P4kRejectionReason#tagValue()} (REQ-P4K-001, REQ-OBS-022).
   */
  public static final String P4K_IMPORT_REJECTED = "basetool.p4k.import.rejected";

  /** Tag key: the external-sync source system ({@code SyncSourceSystem#name()}). */
  public static final String TAG_SOURCE = "source";

  /**
   * Tag key: the catalogue whose orphan sweep stood down, on {@link
   * #CATALOGUE_ORPHAN_SWEEP_SKIPPED} ({@code item} / {@code vehicle} / {@code commodity} / {@code
   * blueprint} / {@code manufacturer}). Bounded literal set, mirroring {@link
   * MetricNames#TAG_STEP}.
   */
  public static final String TAG_SWEEP = "sweep";

  /** Catalogue identity of the cross-kind game-item sweep, on {@link #TAG_SWEEP}. */
  public static final String SWEEP_ITEM = "item";

  /** Orphan-sweep stand-down: at least one kind pass came back an incomplete census. */
  public static final String SWEEP_SKIP_INCOMPLETE = "incomplete";

  /** Orphan-sweep stand-down: the run ended up with no rows at all, so a sweep would wipe. */
  public static final String SWEEP_SKIP_NO_ROWS = "no_rows";

  /**
   * {@link MetricNames#TAG_REASON} value on {@link #EXTERNAL_FETCH_ERRORS}: a transport, decode or
   * envelope failure.
   */
  public static final String REASON_FETCH = "fetch";

  /**
   * {@link MetricNames#TAG_REASON} value on {@link #EXTERNAL_FETCH_ERRORS}: a page walk whose
   * census could not be vouched for.
   */
  public static final String REASON_CENSUS = "census";

  /** {@code source} value for the UEX API client ({@link #EXTERNAL_FETCH_ERRORS}). */
  public static final String SOURCE_UEX = "uex";

  /** {@code source} value for the Star Citizen Wiki client ({@link #EXTERNAL_FETCH_ERRORS}). */
  public static final String SOURCE_SCWIKI = "scwiki";

  /** Counter of durable catalogue attempts, tagged by source, aggregate and status. */
  public static final String CATALOGUE_ATTEMPTS = "basetool.catalogue.attempts";

  /** Gauge identifying catalogue attempts active in this process. */
  public static final String CATALOGUE_ATTEMPT_ACTIVE = "basetool.catalogue.attempt.active";

  /** Gauge of the current attempt outcome, tagged by source, aggregate and status. */
  public static final String CATALOGUE_ATTEMPT_STATUS = "basetool.catalogue.attempt.status";

  /** Gauge of the newest attempt start, in epoch seconds. */
  public static final String CATALOGUE_ATTEMPT_STARTED =
      "basetool.catalogue.attempt.started.timestamp.seconds";

  /** Gauge of the newest completely applied attempt, in epoch seconds. */
  public static final String CATALOGUE_ATTEMPT_LAST_COMPLETE =
      "basetool.catalogue.attempt.last.complete.timestamp.seconds";

  /** Tag naming the bounded independently applied catalogue slice. */
  public static final String TAG_AGGREGATE = "aggregate";

  /** Gauge {@code basetool_p4k_import_due}: {@code 1} while a P4K import is due (REQ-OBS-022). */
  public static final String P4K_IMPORT_DUE = "basetool.p4k.import.due";

  /**
   * Gauge {@code basetool_p4k_import_last_run_rows} — tags {@code kind} and {@code outcome}: the
   * last APPLY run's rows per kind and outcome (REQ-OBS-022).
   */
  public static final String P4K_IMPORT_LAST_RUN_ROWS = "basetool.p4k.import.last.run.rows";

  /** Gauge {@code basetool_p4k_import_last_run_unknown_grades} (REQ-OBS-022). */
  public static final String P4K_IMPORT_LAST_RUN_UNKNOWN_GRADES =
      "basetool.p4k.import.last.run.unknown.grades";

  /** Gauge {@code basetool_p4k_import_last_run_unknown_classes} (REQ-OBS-022). */
  public static final String P4K_IMPORT_LAST_RUN_UNKNOWN_CLASSES =
      "basetool.p4k.import.last.run.unknown.classes";

  /**
   * Gauge {@code basetool_p4k_import_last_run_item_class_null_ratio}: the last run's share of items
   * without {@code itemClass}, 0 to 1 (REQ-OBS-022).
   */
  public static final String P4K_IMPORT_LAST_RUN_ITEM_CLASS_NULL_RATIO =
      "basetool.p4k.import.last.run.item.class.null.ratio";

  /**
   * Gauge {@code basetool_p4k_import_last_run_item_class_null_ratio_change}: the last run's share
   * minus the previous run's (REQ-OBS-022).
   */
  public static final String P4K_IMPORT_LAST_RUN_ITEM_CLASS_NULL_RATIO_CHANGE =
      "basetool.p4k.import.last.run.item.class.null.ratio.change";

  /**
   * Gauge {@code basetool_p4k_catalogue_stamped_rows} — tag {@code kind}: the rows a sweep stamped
   * as gone (REQ-OBS-022).
   */
  public static final String P4K_CATALOGUE_STAMPED_ROWS = "basetool.p4k.catalogue.stamped.rows";

  /** Gauge {@code basetool_p4k_review_cases_open} — tag {@code kind} (REQ-OBS-022). */
  public static final String P4K_REVIEW_CASES_OPEN = "basetool.p4k.review.cases.open";

  /** Gauge {@code basetool_p4k_review_case_open_oldest_age_seconds} (REQ-OBS-022). */
  public static final String P4K_REVIEW_CASE_OPEN_OLDEST_AGE =
      "basetool.p4k.review.case.open.oldest.age";

  private CatalogueMetricNames() {}
}
