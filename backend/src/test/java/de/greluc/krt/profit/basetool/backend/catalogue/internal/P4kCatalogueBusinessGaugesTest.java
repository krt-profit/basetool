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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.CatalogueMetricNames;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The P4K catalogue gauges read the last runs, the review cases and the due signal (REQ-OBS-022).
 */
@ExtendWith(MockitoExtension.class)
class P4kCatalogueBusinessGaugesTest {

  @Mock private P4kImportRunRepository runRepository;
  @Mock private ExternalSyncRunRepository syncRunRepository;
  @Mock private GameItemRepository gameItemRepository;
  @Mock private P4kImportChangeRepository changeRepository;
  @Mock private P4kReviewCaseRepository reviewCaseRepository;
  @Mock private P4kStampedRows stampedRows;

  private final P4kCatalogueRevision revision = new P4kCatalogueRevision();
  private SimpleMeterRegistry registry;
  private P4kCatalogueBusinessGauges gauges;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    gauges =
        new P4kCatalogueBusinessGauges(
            new P4kImportDueService(runRepository, syncRunRepository, gameItemRepository),
            runRepository,
            changeRepository,
            reviewCaseRepository,
            stampedRows,
            revision);
    gauges.register(registry);
  }

  @Test
  void beforeTheFirstRun_theImportIsDueAndEveryRunGaugeIsEmpty() {
    gauges.sample();

    assertThat(value(CatalogueMetricNames.P4K_IMPORT_DUE)).isEqualTo(1.0d);
    assertThat(rows(P4kKind.ITEM, "matched")).isZero();
    assertThat(value(CatalogueMetricNames.P4K_IMPORT_LAST_RUN_ITEM_CLASS_NULL_RATIO)).isNaN();
    assertThat(value(CatalogueMetricNames.P4K_IMPORT_LAST_RUN_ITEM_CLASS_NULL_RATIO_CHANGE))
        .isZero();
  }

  @Test
  void theLastRunsCountsAppearPerKindAndOutcome() {
    P4kImportRun last = run(0.4d);
    last.getKinds().add(kind(P4kKind.ITEM, 10, 2, 3, 4, 1, 5));
    when(runRepository.findTop2ByOrderByAppliedAtDesc()).thenReturn(List.of(last));

    gauges.sample();

    assertThat(rows(P4kKind.ITEM, "matched")).isEqualTo(10.0d);
    assertThat(rows(P4kKind.ITEM, "unmatched")).isEqualTo(2.0d);
    assertThat(rows(P4kKind.ITEM, "added")).isEqualTo(3.0d);
    assertThat(rows(P4kKind.ITEM, "vanished")).isEqualTo(4.0d);
    assertThat(rows(P4kKind.ITEM, "reappeared")).isEqualTo(1.0d);
    assertThat(rows(P4kKind.ITEM, "changed")).isEqualTo(5.0d);
    assertThat(rows(P4kKind.SHIP, "matched")).isZero();
  }

  @Test
  void theNullShareAndItsRiseAgainstThePreviousRunAreReported() {
    when(runRepository.findTop2ByOrderByAppliedAtDesc()).thenReturn(List.of(run(0.5d), run(0.4d)));

    gauges.sample();

    assertThat(value(CatalogueMetricNames.P4K_IMPORT_LAST_RUN_ITEM_CLASS_NULL_RATIO))
        .isEqualTo(0.5d);
    assertThat(value(CatalogueMetricNames.P4K_IMPORT_LAST_RUN_ITEM_CLASS_NULL_RATIO_CHANGE))
        .isCloseTo(0.1d, within(1e-9));
  }

  @Test
  void aPreviousRunWithoutAShare_givesNoChange() {
    when(runRepository.findTop2ByOrderByAppliedAtDesc()).thenReturn(List.of(run(0.5d), run(null)));

    gauges.sample();

    assertThat(value(CatalogueMetricNames.P4K_IMPORT_LAST_RUN_ITEM_CLASS_NULL_RATIO_CHANGE))
        .isZero();
  }

  @Test
  void theLastRunsUnknownGradesAndClassesAreCounted() {
    P4kImportRun last = run(null);
    when(runRepository.findTop2ByOrderByAppliedAtDesc()).thenReturn(List.of(last));
    when(changeRepository.countByRunIdAndKindAndChangeTypeAndFieldName(
            last.getId(), P4kKind.ITEM, P4kChangeType.UNKNOWN_VALUE, P4kItemReconciler.GRADE))
        .thenReturn(2L);
    when(changeRepository.countByRunIdAndKindAndChangeTypeAndFieldName(
            last.getId(), P4kKind.ITEM, P4kChangeType.UNKNOWN_VALUE, P4kItemReconciler.ITEM_CLASS))
        .thenReturn(3L);

    gauges.sample();

    assertThat(value(CatalogueMetricNames.P4K_IMPORT_LAST_RUN_UNKNOWN_GRADES)).isEqualTo(2.0d);
    assertThat(value(CatalogueMetricNames.P4K_IMPORT_LAST_RUN_UNKNOWN_CLASSES)).isEqualTo(3.0d);
  }

  @Test
  void openReviewCasesAndStampedRowsArePerKind_andTheOldestCaseHasAnAge() {
    when(reviewCaseRepository.countByStatusAndKind(eq(P4kReviewStatus.OPEN), any()))
        .thenAnswer(call -> call.getArgument(1) == P4kKind.SHIP ? 4L : 0L);
    when(reviewCaseRepository.findOldestCreatedAtByStatus(P4kReviewStatus.OPEN))
        .thenReturn(Instant.now().minus(31, ChronoUnit.DAYS));
    when(stampedRows.count(any()))
        .thenAnswer(call -> call.getArgument(0) == P4kKind.COMMODITY ? 7L : 0L);

    gauges.sample();

    assertThat(kindValue(CatalogueMetricNames.P4K_REVIEW_CASES_OPEN, P4kKind.SHIP)).isEqualTo(4.0d);
    assertThat(kindValue(CatalogueMetricNames.P4K_REVIEW_CASES_OPEN, P4kKind.ITEM)).isZero();
    assertThat(kindValue(CatalogueMetricNames.P4K_CATALOGUE_STAMPED_ROWS, P4kKind.COMMODITY))
        .isEqualTo(7.0d);
    assertThat(value(CatalogueMetricNames.P4K_REVIEW_CASE_OPEN_OLDEST_AGE))
        .isGreaterThan(30 * 86_400.0d);
  }

  @Test
  void theCountsAreReadAgainOnlyAfterAP4kWrite_whileTheDueSignalIsReadEveryTime() {
    when(stampedRows.count(any())).thenReturn(1L);
    gauges.sample();
    when(stampedRows.count(any())).thenReturn(2L);

    gauges.sample();
    assertThat(kindValue(CatalogueMetricNames.P4K_CATALOGUE_STAMPED_ROWS, P4kKind.ITEM))
        .isEqualTo(1.0d);

    revision.changed();
    gauges.sample();
    assertThat(kindValue(CatalogueMetricNames.P4K_CATALOGUE_STAMPED_ROWS, P4kKind.ITEM))
        .isEqualTo(2.0d);
    verify(runRepository, times(2)).findTop2ByOrderByAppliedAtDesc();
    verify(gameItemRepository, times(3)).findDistinctUexGameVersions();
  }

  @Test
  void everyGaugeCarriesOnlyKindAndOutcome() {
    for (Meter meter : registry.getMeters()) {
      assertThat(meter.getId().getTags())
          .allSatisfy(
              tag -> assertThat(tag.getKey()).isIn(MetricNames.TAG_KIND, MetricNames.TAG_OUTCOME));
    }
  }

  private static P4kImportRun run(Double itemClassNullShare) {
    P4kImportRun run = new P4kImportRun();
    run.setId(UUID.randomUUID());
    run.setItemClassNullShare(itemClassNullShare);
    return run;
  }

  private static P4kImportRunKind kind(
      P4kKind kind,
      int matched,
      int unmatched,
      int added,
      int vanished,
      int reappeared,
      int changed) {
    P4kImportRunKind row = new P4kImportRunKind();
    row.setKind(kind);
    row.setMatched(matched);
    row.setUnmatched(unmatched);
    row.setAdded(added);
    row.setVanished(vanished);
    row.setReappeared(reappeared);
    row.setChanged(changed);
    return row;
  }

  private double value(String name) {
    return registry.get(name).gauge().value();
  }

  private double rows(P4kKind kind, String outcome) {
    return registry
        .get(CatalogueMetricNames.P4K_IMPORT_LAST_RUN_ROWS)
        .tag(MetricNames.TAG_KIND, kind.name())
        .tag(MetricNames.TAG_OUTCOME, outcome)
        .gauge()
        .value();
  }

  private double kindValue(String name, P4kKind kind) {
    return registry.get(name).tag(MetricNames.TAG_KIND, kind.name()).gauge().value();
  }
}
