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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.CatalogueMetricNames;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGaugeSource;
import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGauges;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The P4K catalogue's business gauges (REQ-OBS-022): whether an import is due, what the last APPLY
 * run did per kind and outcome, its unknown grade and class values, its {@code itemClass} null
 * share and that share's change against the previous run, the stamped rows and the open review
 * cases. Tags are the bounded {@code kind} and {@code outcome} only; the mass guard has no gauge.
 */
@Component
@RequiredArgsConstructor
public class P4kCatalogueBusinessGauges implements BusinessGaugeSource {

  /** The {@code field_name} of an item's unknown grade in the change report (REQ-P4K-018). */
  static final String UNKNOWN_GRADE_FIELD = "grade";

  /** The {@code field_name} of an item's unknown {@code itemClass} (REQ-P4K-019). */
  static final String UNKNOWN_CLASS_FIELD = "itemClass";

  private final P4kImportDueService dueService;
  private final P4kImportRunRepository runRepository;
  private final P4kImportChangeRepository changeRepository;
  private final P4kReviewCaseRepository reviewCaseRepository;
  private final P4kStampedRows stampedRows;

  private final AtomicLong due = new AtomicLong();
  private final List<KindGauges> kindGauges = new ArrayList<>();
  private final AtomicLong unknownGrades = new AtomicLong();
  private final AtomicLong unknownClasses = new AtomicLong();
  private final AtomicReference<Double> classNullRatio = new AtomicReference<>(Double.NaN);
  private final AtomicReference<Double> classNullRatioChange = new AtomicReference<>(0.0d);
  private final AtomicLong oldestOpenCaseAge = new AtomicLong();

  @Override
  public void register(@NotNull MeterRegistry registry) {
    BusinessGauges.countGauge(registry, CatalogueMetricNames.P4K_IMPORT_DUE, due);
    for (P4kKind kind : P4kKind.values()) {
      Map<RunOutcome, AtomicLong> rows = new EnumMap<>(RunOutcome.class);
      for (RunOutcome outcome : RunOutcome.values()) {
        AtomicLong holder = new AtomicLong();
        rows.put(outcome, holder);
        Gauge.builder(
                CatalogueMetricNames.P4K_IMPORT_LAST_RUN_ROWS, holder, AtomicLong::doubleValue)
            .tag(MetricNames.TAG_KIND, kind.name())
            .tag(MetricNames.TAG_OUTCOME, outcome.tagValue())
            .register(registry);
      }
      kindGauges.add(
          new KindGauges(
              kind,
              rows,
              kindGauge(registry, CatalogueMetricNames.P4K_CATALOGUE_STAMPED_ROWS, kind),
              kindGauge(registry, CatalogueMetricNames.P4K_REVIEW_CASES_OPEN, kind)));
    }
    BusinessGauges.countGauge(
        registry, CatalogueMetricNames.P4K_IMPORT_LAST_RUN_UNKNOWN_GRADES, unknownGrades);
    BusinessGauges.countGauge(
        registry, CatalogueMetricNames.P4K_IMPORT_LAST_RUN_UNKNOWN_CLASSES, unknownClasses);
    Gauge.builder(
            CatalogueMetricNames.P4K_IMPORT_LAST_RUN_ITEM_CLASS_NULL_RATIO,
            classNullRatio,
            AtomicReference::get)
        .register(registry);
    Gauge.builder(
            CatalogueMetricNames.P4K_IMPORT_LAST_RUN_ITEM_CLASS_NULL_RATIO_CHANGE,
            classNullRatioChange,
            AtomicReference::get)
        .register(registry);
    BusinessGauges.ageGauge(
        registry, CatalogueMetricNames.P4K_REVIEW_CASE_OPEN_OLDEST_AGE, oldestOpenCaseAge);
  }

  @Override
  public void sample() {
    due.set(dueService.evaluate().due() ? 1L : 0L);
    List<P4kImportRun> lastTwo = runRepository.findTop2ByOrderByAppliedAtDesc();
    P4kImportRun last = lastTwo.isEmpty() ? null : lastTwo.getFirst();
    P4kImportRun previous = lastTwo.size() > 1 ? lastTwo.get(1) : null;
    sampleUnknownValues(last);
    sampleClassNullShare(last, previous);
    Map<P4kKind, P4kImportRunKind> lastRows = new EnumMap<>(P4kKind.class);
    if (last != null) {
      last.getKinds().forEach(row -> lastRows.put(row.getKind(), row));
    }
    for (KindGauges gauges : kindGauges) {
      P4kImportRunKind row = lastRows.get(gauges.kind());
      gauges.rows().forEach((outcome, holder) -> holder.set(row == null ? 0L : outcome.count(row)));
      gauges.stamped().set(stampedRows.count(gauges.kind()));
      gauges
          .openCases()
          .set(reviewCaseRepository.countByStatusAndKind(P4kReviewStatus.OPEN, gauges.kind()));
    }
    oldestOpenCaseAge.set(
        BusinessGauges.ageSeconds(
            reviewCaseRepository.findOldestCreatedAtByStatus(P4kReviewStatus.OPEN)));
  }

  /**
   * Writes the last run's unknown grade and class values.
   *
   * @param last the last run, or {@code null} before the first
   */
  private void sampleUnknownValues(@Nullable P4kImportRun last) {
    unknownGrades.set(last == null ? 0L : unknownItemValues(last, UNKNOWN_GRADE_FIELD));
    unknownClasses.set(last == null ? 0L : unknownItemValues(last, UNKNOWN_CLASS_FIELD));
  }

  private long unknownItemValues(@NotNull P4kImportRun run, @NotNull String field) {
    return changeRepository.countByRunIdAndKindAndChangeTypeAndFieldName(
        run.getId(), P4kKind.ITEM, P4kChangeType.UNKNOWN_VALUE, field);
  }

  /**
   * Writes the last run's {@code itemClass} null share, {@code NaN} when unknown, and its change
   * against the previous run, {@code 0} when either share is unknown.
   *
   * @param last the last run, or {@code null}
   * @param previous the run before it, or {@code null}
   */
  private void sampleClassNullShare(@Nullable P4kImportRun last, @Nullable P4kImportRun previous) {
    Double lastShare = last == null ? null : last.getItemClassNullShare();
    Double previousShare = previous == null ? null : previous.getItemClassNullShare();
    classNullRatio.set(lastShare == null ? Double.NaN : lastShare);
    classNullRatioChange.set(
        lastShare == null || previousShare == null ? 0.0d : lastShare - previousShare);
  }

  @NotNull
  private static AtomicLong kindGauge(
      @NotNull MeterRegistry registry, @NotNull String name, @NotNull P4kKind kind) {
    AtomicLong holder = new AtomicLong();
    Gauge.builder(name, holder, AtomicLong::doubleValue)
        .tag(MetricNames.TAG_KIND, kind.name())
        .register(registry);
    return holder;
  }

  /**
   * The gauge holders of one kind.
   *
   * @param kind the kind
   * @param rows the last run's count per outcome
   * @param stamped the stamped rows
   * @param openCases the open review cases
   */
  private record KindGauges(
      @NotNull P4kKind kind,
      @NotNull Map<RunOutcome, AtomicLong> rows,
      @NotNull AtomicLong stamped,
      @NotNull AtomicLong openCases) {}

  /** The six result counts of a run's kind, each an {@code outcome} tag value. */
  private enum RunOutcome {
    MATCHED,
    UNMATCHED,
    ADDED,
    VANISHED,
    REAPPEARED,
    CHANGED;

    long count(@NotNull P4kImportRunKind kindRow) {
      return switch (this) {
        case MATCHED -> kindRow.getMatched();
        case UNMATCHED -> kindRow.getUnmatched();
        case ADDED -> kindRow.getAdded();
        case VANISHED -> kindRow.getVanished();
        case REAPPEARED -> kindRow.getReappeared();
        case CHANGED -> kindRow.getChanged();
      };
    }

    @NotNull
    String tagValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }
}
