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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.catalogue.api.CatalogueMetricNames;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kChangeNumber;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/** An APPLY never rolls the catalogue back to an older Build (REQ-P4K-004). */
@SpringBootTest
@Transactional
class P4kImportServiceBuildOrderTest {

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private P4kImportRunRepository runRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private MeterRegistry meterRegistry;

  private P4kImportRuns runs;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
  }

  private double olderBuildRefusals() {
    Counter counter =
        meterRegistry
            .find(CatalogueMetricNames.P4K_IMPORT_REJECTED)
            .tag("reason", "older_build")
            .counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void anOlderBuild_isRefusedAndWritesNothing() {
    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    GameItem shield = new GameItem();
    shield.setName("Build order shield");
    shield.setClassName(P4kTestCatalogues.SHIELD_CLASS);
    gameItemRepository.save(shield);
    long runsBefore = runRepository.count();
    double refusalsBefore = olderBuildRefusals();

    assertThatThrownBy(() -> runs.apply(P4kTestCatalogues.withChangeNumber("12900000"), false))
        .isInstanceOf(BadRequestException.class);

    assertThat(runRepository.count()).isEqualTo(runsBefore);
    assertThat(shield.getP4kUuid()).isNull();
    assertThat(olderBuildRefusals()).isEqualTo(refusalsBefore + 1);
  }

  @Test
  void aPreviewOfAnOlderBuild_isRefusedLikeItsApply() {
    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    double refusalsBefore = olderBuildRefusals();

    assertThatThrownBy(() -> runs.previewWithReport(P4kTestCatalogues.withChangeNumber("12900000")))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("older than the last applied one");

    assertThat(olderBuildRefusals()).isEqualTo(refusalsBefore + 1);
  }

  @Test
  void theSameBuild_appliesAgain_andChangesNoValueASecondTime() {
    GameItem shield = new GameItem();
    shield.setName("Same build shield");
    shield.setClassName(P4kTestCatalogues.SHIELD_CLASS);
    gameItemRepository.save(shield);
    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    String description = shield.getDescriptionEn();
    String nameKey = shield.getNameKey();

    P4kImportResultDto again = runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(again.items().enriched()).isZero();
    assertThat(shield.getDescriptionEn()).isEqualTo(description);
    assertThat(shield.getNameKey()).isEqualTo(nameKey);
  }

  @Test
  void changeNumbers_compareAsNumbers_notAsText() {
    assertThat(P4kChangeNumber.parse("9")).isLessThan(P4kChangeNumber.parse("10"));
    runs.apply(P4kTestCatalogues.withChangeNumber("10"), false);

    assertThatThrownBy(() -> runs.apply(P4kTestCatalogues.withChangeNumber("9"), false))
        .isInstanceOf(BadRequestException.class);
  }
}
