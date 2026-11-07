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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDomain;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditService;
import de.greluc.krt.profit.basetool.backend.catalogue.api.City;
import de.greluc.krt.profit.basetool.backend.catalogue.api.CityRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * The UEX locations link to the starmap by normalised name, type and linked parent; ambiguity goes
 * to review and a manual link survives every APPLY (REQ-P4K-035).
 */
@SpringBootTest
@Transactional
class P4kStarmapLinkTest {

  private static final UUID SYSTEM = UUID.fromString("5a000000-0000-4000-8000-000000000011");
  private static final UUID HURSTON = UUID.fromString("5a000000-0000-4000-8000-000000000012");
  private static final UUID LORVILLE = UUID.fromString("5a000000-0000-4000-8000-000000000013");
  private static final UUID ARCCORP = UUID.fromString("5a000000-0000-4000-8000-000000000014");
  private static final UUID OTHER_LORVILLE =
      UUID.fromString("5a000000-0000-4000-8000-000000000015");

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private StarmapObjectRepository objectRepository;
  @Autowired private StarSystemRepository starSystemRepository;
  @Autowired private PlanetRepository planetRepository;
  @Autowired private CityRepository cityRepository;
  @Autowired private StarmapPlaceRows placeRows;
  @Autowired private StarmapAdminService adminService;
  @Autowired private P4kReviewCaseRepository caseRepository;
  @Autowired private AuditService auditService;

  private P4kImportRuns runs;
  private P4kStarmapFixtures fixtures;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
    fixtures = new P4kStarmapFixtures(starSystemRepository, planetRepository, cityRepository);
    fixtures.starSystem(P4kStarmapFixtures.SYSTEM, true);
    fixtures.planet("Krt Hurston", P4kStarmapFixtures.SYSTEM);
  }

  private byte[] tree() {
    return P4kTestCatalogues.bytes(
        P4kStarmapFixtures.catalogue(
            P4kStarmapFixtures.object(SYSTEM, "SolarSystem", P4kStarmapFixtures.SYSTEM, null),
            P4kStarmapFixtures.object(HURSTON, "Planet", "Krt Hurston", SYSTEM),
            P4kStarmapFixtures.object(ARCCORP, "Planet", "Krt ArcCorp", SYSTEM),
            P4kStarmapFixtures.object(LORVILLE, "LandingZone", "Krt Lorville", HURSTON),
            P4kStarmapFixtures.object(OTHER_LORVILLE, "LandingZone", "Krt Lorville", ARCCORP)));
  }

  private UUID linkOf(StarmapPlaceKind kind, UUID rowId) {
    return placeRows.row(kind, rowId).orElseThrow().starmapObjectId();
  }

  private UUID idOf(UUID guid) {
    return objectRepository.findByP4kUuid(guid).orElseThrow().getId();
  }

  @Test
  void aCityWhoseNameTypeAndLinkedPlanetMatchOneObject_isLinked() {
    City city = fixtures.city("Krt Lorville", P4kStarmapFixtures.SYSTEM, "Krt Hurston");

    runs.apply(tree(), false);

    assertThat(linkOf(StarmapPlaceKind.CITY, city.getId())).isEqualTo(idOf(LORVILLE));
  }

  @Test
  void twoCandidates_openOneReviewCase_andLinkNothing() {
    City city = fixtures.city("Krt Lorville", P4kStarmapFixtures.SYSTEM, "Krt Unknown Planet");

    P4kImportResultDto result = runs.apply(tree(), false);
    runs.apply(tree(), false);

    assertThat(linkOf(StarmapPlaceKind.CITY, city.getId())).isNull();
    assertThat(
            caseRepository.findByKindAndGuidAndReason(
                P4kKind.STARMAP_OBJECT,
                P4kStarmapLinker.caseGuid(StarmapPlaceKind.CITY, city.getId()),
                P4kReviewReason.STARMAP_LINK_AMBIGUOUS))
        .hasValueSatisfying(c -> assertThat(c.getStatus()).isEqualTo(P4kReviewStatus.OPEN));
    assertThat(result.starmapObjects()).isNotNull();
    assertThat(result.starmapObjects().unmatched()).isPositive();
  }

  @Test
  void aManualLink_survivesAnApplyThatWouldLinkElsewhere() {
    City city = fixtures.city("Krt Lorville", P4kStarmapFixtures.SYSTEM, "Krt Hurston");
    runs.apply(tree(), false);
    adminService.changeLink(
        StarmapPlaceKind.CITY,
        city.getId(),
        StarmapLinkAction.SET,
        idOf(OTHER_LORVILLE),
        idOf(LORVILLE),
        false);

    runs.apply(tree(), false);

    assertThat(linkOf(StarmapPlaceKind.CITY, city.getId())).isEqualTo(idOf(OTHER_LORVILLE));
    assertThat(
            auditService
                .getEvents(
                    AuditDomain.CATALOGUE,
                    null,
                    null,
                    null,
                    AuditEventType.CATALOGUE_STARMAP_LINK_SET,
                    null,
                    PageRequest.of(0, 50))
                .getContent())
        .filteredOn(e -> city.getId().equals(e.subjectId()))
        .singleElement()
        .satisfies(e -> assertThat(e.details()).contains(idOf(OTHER_LORVILLE).toString()));
  }

  @Test
  void aCaseALaterRunNoLongerFinds_isClosedAsResolved() {
    City city = fixtures.city("Krt Lorville", P4kStarmapFixtures.SYSTEM, "Krt Unknown Planet");
    runs.apply(tree(), false);

    adminService.changeLink(
        StarmapPlaceKind.CITY, city.getId(), StarmapLinkAction.SET, idOf(LORVILLE), null, false);
    runs.apply(tree(), false);

    assertThat(
            caseRepository.findByKindAndGuidAndReason(
                P4kKind.STARMAP_OBJECT,
                P4kStarmapLinker.caseGuid(StarmapPlaceKind.CITY, city.getId()),
                P4kReviewReason.STARMAP_LINK_AMBIGUOUS))
        .hasValueSatisfying(
            c -> assertThat(c.getResolution()).isEqualTo(P4kReviewResolution.RESOLVED));
  }
}
