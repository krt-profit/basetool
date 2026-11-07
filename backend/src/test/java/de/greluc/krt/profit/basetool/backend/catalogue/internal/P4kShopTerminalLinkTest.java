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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDomain;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditService;
import de.greluc.krt.profit.basetool.backend.catalogue.api.CityRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Terminal;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShopDto;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A shop links to the UEX terminal of its place whose name matches it; ambiguity and a missing
 * terminal go to review, and a manual link survives every APPLY (REQ-P4K-043).
 */
@SpringBootTest
@Transactional
class P4kShopTerminalLinkTest {

  private static final UUID SYSTEM = UUID.fromString("5b000000-0000-4000-8000-000000000011");
  private static final UUID ARCCORP = UUID.fromString("5b000000-0000-4000-8000-000000000012");
  private static final UUID AREA18 = UUID.fromString("5b000000-0000-4000-8000-000000000013");

  /** The key of the test shop; its last element is the shop entity. */
  private static final String KEY =
      "5b000000-0000-4000-8000-0000000000a1,5b000000-0000-4000-8000-0000000000a2";

  /** The place name every test row uses. */
  private static final String CITY = "Krt Area18";

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private StarSystemRepository starSystemRepository;
  @Autowired private PlanetRepository planetRepository;
  @Autowired private CityRepository cityRepository;
  @Autowired private TerminalRepository terminalRepository;
  @Autowired private ShopRepository shopRepository;
  @Autowired private ShopAdminService adminService;
  @Autowired private P4kReviewCaseRepository caseRepository;
  @Autowired private AuditService auditService;
  @Autowired private EntityManager entityManager;

  private P4kImportRuns runs;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
    P4kStarmapFixtures fixtures =
        new P4kStarmapFixtures(starSystemRepository, planetRepository, cityRepository);
    fixtures.starSystem(P4kStarmapFixtures.SYSTEM, true);
    fixtures.planet("Krt ArcCorp", P4kStarmapFixtures.SYSTEM);
    fixtures.city(CITY, P4kStarmapFixtures.SYSTEM, "Krt ArcCorp");
  }

  /**
   * The catalogue with the test star system, ArcCorp, Area 18 and one Cubby Blast shop.
   *
   * @param place the shop's starmap object, or {@code null}
   * @return the plain JSON bytes
   */
  private static byte @NotNull [] catalogue(@Nullable UUID place) {
    ObjectNode root =
        P4kStarmapFixtures.catalogue(
            P4kStarmapFixtures.object(SYSTEM, "SolarSystem", P4kStarmapFixtures.SYSTEM, null),
            P4kStarmapFixtures.object(ARCCORP, "Planet", "Krt ArcCorp", SYSTEM),
            P4kStarmapFixtures.object(AREA18, "LandingZone", CITY, ARCCORP));
    ArrayNode shops = root.putArray("shops");
    shops.add(P4kShopFixtures.shop(KEY, P4kShopFixtures.CUBBY_BLAST, place));
    return P4kTestCatalogues.bytes(root);
  }

  /**
   * Stores a UEX terminal in Area 18.
   *
   * @param name the UEX name
   * @return the terminal
   */
  private @NotNull Terminal terminal(@NotNull String name) {
    Terminal terminal = new Terminal();
    terminal.setName(name);
    terminal.setStarSystemName(P4kStarmapFixtures.SYSTEM);
    terminal.setPlanetName("Krt ArcCorp");
    terminal.setCityName(CITY);
    return terminalRepository.saveAndFlush(terminal);
  }

  /**
   * Reads the test shop as stored.
   *
   * @return the shop
   */
  private @NotNull Shop shop() {
    entityManager.flush();
    entityManager.clear();
    UUID guid = P4kShopDto.guidOf(KEY);
    return shopRepository.findAll().stream()
        .filter(s -> guid.equals(s.getP4kUuid()))
        .findFirst()
        .orElseThrow();
  }

  /**
   * Whether the test shop has an open case of a reason.
   *
   * @param reason the reason
   * @return {@code true} when the case exists and is open
   */
  private boolean openCase(@NotNull P4kReviewReason reason) {
    return caseRepository
        .findByKindAndGuidAndReason(P4kKind.SHOP, P4kShopDto.guidOf(KEY), reason)
        .filter(c -> c.getStatus() == P4kReviewStatus.OPEN)
        .isPresent();
  }

  @Test
  void aShopWhosePlaceHoldsOneTerminalOfTheMatchingName_isLinked() {
    Terminal cubby = terminal("Cubby Blast - Krt Area18");
    terminal("Dumper's Depot - Krt Area18");

    runs.apply(catalogue(AREA18), false);

    assertThat(shop().getTerminalId()).isEqualTo(cubby.getId());
    assertThat(openCase(P4kReviewReason.SHOP_LINK_NOT_FOUND)).isFalse();
  }

  @Test
  void twoCandidates_openOneReviewCase_andLinkNothing() {
    terminal("Cubby Blast - Krt Area18");
    terminal("Cubby Blast - Krt Area18 Plaza");

    P4kImportResultDto result = runs.apply(catalogue(AREA18), false);

    assertThat(shop().getTerminalId()).isNull();
    assertThat(openCase(P4kReviewReason.SHOP_LINK_AMBIGUOUS)).isTrue();
    assertThat(result.shops()).isNotNull();
    assertThat(result.shops().unmatched()).isEqualTo(1);
  }

  @Test
  void aShopWithoutAPlace_isOnlyReportedUnmatched_andOpensNoCase() {
    terminal("Cubby Blast - Krt Area18");

    P4kImportResultDto result = runs.apply(catalogue(null), false);

    assertThat(shop().getTerminalId()).isNull();
    assertThat(result.shops()).isNotNull();
    assertThat(result.shops().unmatched()).isEqualTo(1);
    assertThat(result.shops().review()).isZero();
    assertThat(openCase(P4kReviewReason.SHOP_LINK_NOT_FOUND)).isFalse();
    assertThat(openCase(P4kReviewReason.SHOP_LINK_AMBIGUOUS)).isFalse();
  }

  @Test
  void aShopWhosePlaceHoldsNoMatchingTerminal_opensANotFoundCase() {
    terminal("Dumper's Depot - Krt Area18");

    runs.apply(catalogue(AREA18), false);

    assertThat(shop().getTerminalId()).isNull();
    assertThat(openCase(P4kReviewReason.SHOP_LINK_NOT_FOUND)).isTrue();
  }

  @Test
  void aManualLink_survivesTheNextApply_andIsAudited() {
    Terminal cubby = terminal("Cubby Blast - Krt Area18");
    Terminal other = terminal("Admin - Krt Area18");
    runs.apply(catalogue(AREA18), false);
    adminService.changeLink(
        shop().getId(), StarmapLinkAction.SET, other.getId(), cubby.getId(), false);

    runs.apply(catalogue(AREA18), false);

    Shop shop = shop();
    assertThat(shop.getTerminalId()).isEqualTo(other.getId());
    assertThat(shop.isTerminalLinkManual()).isTrue();
    assertThat(
            auditService
                .getEvents(
                    AuditDomain.CATALOGUE,
                    null,
                    null,
                    null,
                    AuditEventType.CATALOGUE_SHOP_LINK_SET,
                    null,
                    PageRequest.of(0, 50))
                .getContent())
        .filteredOn(e -> shop.getId().equals(e.subjectId()))
        .singleElement()
        .satisfies(e -> assertThat(e.details()).contains(other.getId().toString()));
  }

  @Test
  void aReleasedLink_isDecidedByTheNextApply_andAStaleEchoIsAConflict() {
    Terminal cubby = terminal("Cubby Blast - Krt Area18");
    runs.apply(catalogue(AREA18), false);
    UUID shopId = shop().getId();
    adminService.changeLink(shopId, StarmapLinkAction.CLEAR, null, cubby.getId(), false);

    assertThatThrownBy(() ->
            adminService.changeLink(shopId, StarmapLinkAction.RELEASE, null, cubby.getId(), true))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    adminService.changeLink(shopId, StarmapLinkAction.RELEASE, null, null, true);
    runs.apply(catalogue(AREA18), false);

    assertThat(shop().getTerminalId()).isEqualTo(cubby.getId());
    assertThat(shop().isTerminalLinkManual()).isFalse();
  }

  @Test
  void aCaseALaterRunNoLongerFinds_isClosedAsResolved() {
    runs.apply(catalogue(AREA18), false);
    assertThat(openCase(P4kReviewReason.SHOP_LINK_NOT_FOUND)).isTrue();
    terminal("Cubby Blast - Krt Area18");

    runs.apply(catalogue(AREA18), false);

    assertThat(
            caseRepository.findByKindAndGuidAndReason(
                P4kKind.SHOP, P4kShopDto.guidOf(KEY), P4kReviewReason.SHOP_LINK_NOT_FOUND))
        .hasValueSatisfying(
            c -> assertThat(c.getResolution()).isEqualTo(P4kReviewResolution.RESOLVED));
  }
}
