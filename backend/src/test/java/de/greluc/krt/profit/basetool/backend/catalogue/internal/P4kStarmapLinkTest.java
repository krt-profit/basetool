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
import de.greluc.krt.profit.basetool.backend.catalogue.api.SpaceStation;
import de.greluc.krt.profit.basetool.backend.catalogue.api.SpaceStationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Terminal;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * The UEX locations link to the starmap by normalised name, type and linked parent; ambiguity goes
 * to review unless the case could settle nothing, and a manual link survives every APPLY
 * (REQ-P4K-035, REQ-P4K-060).
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

  /** The UEX id the test planet Hurston and its orbit share. */
  private static final int HURSTON_UEX_ID = 990_116;

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private StarmapObjectRepository objectRepository;
  @Autowired private StarSystemRepository starSystemRepository;
  @Autowired private PlanetRepository planetRepository;
  @Autowired private CityRepository cityRepository;
  @Autowired private OrbitRepository orbitRepository;
  @Autowired private SpaceStationRepository stationRepository;
  @Autowired private TerminalRepository terminalRepository;
  @Autowired private StarmapPlaceRows placeRows;
  @Autowired private StarmapAdminService adminService;
  @Autowired private P4kReviewCaseRepository caseRepository;
  @Autowired private AuditService auditService;

  private P4kImportRuns runs;
  private P4kStarmapFixtures fixtures;
  private Planet hurston;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
    fixtures = new P4kStarmapFixtures(starSystemRepository, planetRepository, cityRepository);
    fixtures.starSystem(P4kStarmapFixtures.SYSTEM, true);
    hurston = fixtures.planet("Krt Hurston", P4kStarmapFixtures.SYSTEM);
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

  /**
   * Reads a row's link case of a reason.
   *
   * @param kind the table
   * @param rowId the row
   * @param reason the reason
   * @return the case, if the import ever opened it
   */
  private Optional<P4kReviewCase> linkCase(
      StarmapPlaceKind kind, UUID rowId, P4kReviewReason reason) {
    return caseRepository.findByKindAndGuidAndReason(
        P4kKind.STARMAP_OBJECT, P4kStarmapLinker.caseGuid(kind, rowId), reason);
  }

  /**
   * Saves a UEX space station on Hurston.
   *
   * @param name the name
   * @param live whether UEX reports it live
   * @return the row
   */
  private SpaceStation station(String name, boolean live) {
    SpaceStation station = new SpaceStation();
    station.setName(name);
    station.setStarSystemName(P4kStarmapFixtures.SYSTEM);
    station.setPlanetName("Krt Hurston");
    station.setIsAvailableLive(live);
    return stationRepository.saveAndFlush(station);
  }

  /**
   * Saves a UEX terminal in Lorville.
   *
   * @param name the name
   * @return the row
   */
  private Terminal terminal(String name) {
    Terminal terminal = new Terminal();
    terminal.setName(name);
    terminal.setStarSystemName(P4kStarmapFixtures.SYSTEM);
    terminal.setPlanetName("Krt Hurston");
    terminal.setCityName("Krt Lorville");
    return terminalRepository.saveAndFlush(terminal);
  }

  /**
   * Saves a UEX orbit of the test star system.
   *
   * @param name the name
   * @param uexId the UEX id
   * @return the row
   */
  private Orbit orbit(String name, int uexId) {
    Orbit orbit = new Orbit();
    orbit.setName(name);
    orbit.setIdOrbit(uexId);
    orbit.setStarSystemName(P4kStarmapFixtures.SYSTEM);
    return orbitRepository.saveAndFlush(orbit);
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
  void theUnlinkedOnlyList_pagesRowsThatLinkNothing() {
    City city = fixtures.city("Krt Lorville", P4kStarmapFixtures.SYSTEM, "Krt Unknown Planet");

    assertThat(
            adminService
                .places(StarmapPlaceKind.CITY, "krt lorville", true, PageRequest.of(0, 20))
                .getContent())
        .singleElement()
        .satisfies(row -> {
          assertThat(row.id()).isEqualTo(city.getId());
          assertThat(row.starmapObjectId()).isNull();
          assertThat(row.starmapObjectGuid()).isNull();
        });
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

  @Test
  void aRowOfAStarSystemThatLinksToNoObject_linksNothing_andOpensNoCase() {
    fixtures.starSystem("Krtellis", false);
    Planet foreign = fixtures.planet("Krt ArcCorp", "Krtellis");

    P4kImportResultDto result = runs.apply(tree(), false);

    assertThat(linkOf(StarmapPlaceKind.PLANET, foreign.getId())).isNull();
    assertThat(
            linkCase(
                StarmapPlaceKind.PLANET, foreign.getId(), P4kReviewReason.STARMAP_LINK_NOT_FOUND))
        .isEmpty();
    assertThat(result.starmapObjects()).isNotNull();
    assertThat(result.starmapObjects().unmatched()).isPositive();
  }

  @Test
  void aTerminal_isNeverNameMatched_andOpensNoCase_whileAnAdminsLinkStays() {
    fixtures.city("Krt Lorville", P4kStarmapFixtures.SYSTEM, "Krt Hurston");
    Terminal named = terminal("Krt Lorville");
    Terminal manual = terminal("Krt Admin - Krt Lorville");
    runs.apply(tree(), false);
    adminService.changeLink(
        StarmapPlaceKind.TERMINAL,
        manual.getId(),
        StarmapLinkAction.SET,
        idOf(LORVILLE),
        null,
        false);

    runs.apply(tree(), false);

    assertThat(linkOf(StarmapPlaceKind.TERMINAL, named.getId())).isNull();
    assertThat(linkOf(StarmapPlaceKind.TERMINAL, manual.getId())).isEqualTo(idOf(LORVILLE));
    assertThat(
            linkCase(
                StarmapPlaceKind.TERMINAL, named.getId(), P4kReviewReason.STARMAP_LINK_NOT_FOUND))
        .isEmpty();
  }

  @Test
  void anOrbitSharingItsUexIdWithAPlanet_takesItsLink_andAnyOtherOrbitOpensNoCase() {
    hurston.setIdPlanet(HURSTON_UEX_ID);
    planetRepository.saveAndFlush(hurston);
    Orbit twin = orbit("Krt Hurston", HURSTON_UEX_ID);
    Orbit lagrange = orbit("Krt Hurston Lagrange Point 1", HURSTON_UEX_ID + 1);

    runs.apply(tree(), false);

    assertThat(linkOf(StarmapPlaceKind.ORBIT, twin.getId())).isEqualTo(idOf(HURSTON));
    assertThat(linkOf(StarmapPlaceKind.ORBIT, lagrange.getId())).isNull();
    assertThat(
            linkCase(
                StarmapPlaceKind.ORBIT, lagrange.getId(), P4kReviewReason.STARMAP_LINK_NOT_FOUND))
        .isEmpty();
  }

  @Test
  void aCaseTheRulesNoLongerRaise_isClosedAsResolved() {
    SpaceStation olisar = station("Krt Port Olisar", true);
    runs.apply(tree(), false);
    assertThat(
            linkCase(
                StarmapPlaceKind.SPACE_STATION,
                olisar.getId(),
                P4kReviewReason.STARMAP_LINK_NOT_FOUND))
        .hasValueSatisfying(c -> assertThat(c.getStatus()).isEqualTo(P4kReviewStatus.OPEN));

    olisar.setIsAvailableLive(false);
    stationRepository.saveAndFlush(olisar);
    runs.apply(tree(), false);

    assertThat(
            linkCase(
                StarmapPlaceKind.SPACE_STATION,
                olisar.getId(),
                P4kReviewReason.STARMAP_LINK_NOT_FOUND))
        .hasValueSatisfying(
            c -> assertThat(c.getResolution()).isEqualTo(P4kReviewResolution.RESOLVED));
  }
}
