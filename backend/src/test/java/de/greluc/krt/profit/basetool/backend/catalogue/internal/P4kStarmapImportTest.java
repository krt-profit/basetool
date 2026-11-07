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

import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * The starmap objects are imported by GUID into a tree, P4K-only objects included, and the import
 * never makes a storage place (REQ-P4K-034, REQ-P4K-036).
 */
@SpringBootTest
@Transactional
class P4kStarmapImportTest {

  private static final UUID SYSTEM = UUID.fromString("5a000000-0000-4000-8000-000000000001");
  private static final UUID PLANET = UUID.fromString("5a000000-0000-4000-8000-000000000002");
  private static final UUID JUMP_POINT = UUID.fromString("5a000000-0000-4000-8000-000000000003");

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private StarmapObjectRepository objectRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private P4kImportChangeRepository changeRepository;

  private P4kImportRuns runs;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
  }

  private StarmapObject stored(UUID guid) {
    return objectRepository.findByP4kUuid(guid).orElseThrow();
  }

  @Test
  void anObjectGetsItsParent_whateverTheOrderOfTheRecords() {
    runs.apply(
        P4kTestCatalogues.bytes(
            P4kStarmapFixtures.catalogue(
                P4kStarmapFixtures.object(PLANET, "Planet", "Krt Hurston", SYSTEM),
                P4kStarmapFixtures.object(SYSTEM, "SolarSystem", "Krt Stanton", null))),
        false);

    StarmapObject planet = stored(PLANET);
    assertThat(planet.getParentId()).isEqualTo(stored(SYSTEM).getId());
    assertThat(planet.getType()).isEqualTo("Planet");
    assertThat(planet.getName()).isEqualTo("Krt Hurston");
    assertThat(planet.getNameDe()).isEqualTo("Krt Hurston (DE)");
    assertThat(planet.hasPosition()).isTrue();
    assertThat(planet.getP4kSeenRunId()).isNotNull();
  }

  @Test
  void aJumpPointWithoutUex_isImported_andIsNoTradeOrStoragePlace() {
    long locations = locationRepository.count();

    P4kImportResultDto result =
        runs.apply(
            P4kTestCatalogues.bytes(
                P4kStarmapFixtures.catalogue(
                    P4kStarmapFixtures.object(JUMP_POINT, "Anomaly", "Krt Pyro Gateway", null))),
            false);

    assertThat(stored(JUMP_POINT).getType()).isEqualTo("Anomaly");
    assertThat(locationRepository.count()).isEqualTo(locations);
    assertThat(result.starmapObjects()).isNotNull();
    assertThat(result.starmapObjects().created()).isEqualTo(1);
  }

  @Test
  void aSecondApply_changesNothing_andTheReportHoldsNoGameText() {
    byte[] catalogue =
        P4kTestCatalogues.bytes(
            P4kStarmapFixtures.catalogue(
                P4kStarmapFixtures.object(SYSTEM, "SolarSystem", "Krt Stanton", null),
                P4kStarmapFixtures.object(PLANET, "Planet", "Krt Hurston", SYSTEM)));
    P4kImportResultDto first = runs.apply(catalogue, false);
    P4kImportResultDto second = runs.apply(catalogue, false);

    assertThat(second.starmapObjects()).isNotNull();
    assertThat(second.starmapObjects().enriched()).isZero();
    assertThat(second.starmapObjects().matched()).isEqualTo(2);
    assertThat(P4kImportRuns.report(changeRepository, first.runId()))
        .filteredOn(c ->
            c.getKind() == P4kKind.STARMAP_OBJECT
                && c.getGuid() != null
                && (c.getGuid().equals(PLANET) || c.getGuid().equals(SYSTEM)))
        .isNotEmpty()
        .allSatisfy(c -> assertThat(c.getNameEn()).isNull());
  }
}
