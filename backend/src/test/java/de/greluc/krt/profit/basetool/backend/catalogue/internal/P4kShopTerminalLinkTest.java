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
 * A shop links to the UEX terminal at or around its place whose name matches it; ambiguity and a
 * missing terminal go to review unless the case could settle nothing, and a manual link survives
 * every APPLY (REQ-P4K-043, REQ-P4K-060).
 */
@SpringBootTest
@Transactional
class P4kShopTerminalLinkTest {

  private static final UUID SYSTEM = UUID.fromString("5b000000-0000-4000-8000-000000000011");
  private static final UUID ARCCORP = UUID.fromString("5b000000-0000-4000-8000-000000000012");
  private static final UUID AREA18 = UUID.fromString("5b000000-0000-4000-8000-000000000013");
  private static final UUID PLAZA = UUID.fromString("5b000000-0000-4000-8000-000000000014");
  private static final UUID OUTPOST = UUID.fromString("5b000000-0000-4000-8000-000000000015");

  /** The key of the test shop; its last element is the shop entity. */
  private static final String KEY =
      "5b000000-0000-4000-8000-0000000000a1,5b000000-0000-4000-8000-0000000000a2";

  /** The key of a second shop of the same place. */
  private static final String OTHER_KEY =
      "5b000000-0000-4000-8000-0000000000a1,5b000000-0000-4000-8000-0000000000a4";

  /** The place name every test row uses. */
  private static final String CITY = "Krt Area18";

  /** The registry name of a pharmacy, which UEX lists as a "Medical Shop". */
  private static final String PHARMACY_REGISTRY = "Pharmacy_Krt_Area18";

  /** The UEX and game name of the test outpost. */
  private static final String OUTPOST_NAME = "Krt Shubin Outpost";

  /** The entity that places the test outpost, which a shop's containers name. */
  private static final String OUTPOST_ENTITY = "krt_shubin_outpost";

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private StarSystemRepository starSystemRepository;
  @Autowired private PlanetRepository planetRepository;
  @Autowired private CityRepository cityRepository;
  @Autowired private OutpostRepository outpostRepository;
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
   * The catalogue with the test star system, ArcCorp, Area 18, a plaza inside it, an outpost on
   * ArcCorp and the given shops.
   *
   * @param shops the shop records
   * @return the plain JSON bytes
   */
  private static byte @NotNull [] catalogue(@NotNull ObjectNode... shops) {
    ObjectNode outpost = P4kStarmapFixtures.object(OUTPOST, "Outpost", OUTPOST_NAME, ARCCORP);
    outpost.put("entityName", OUTPOST_ENTITY);
    ObjectNode root =
        P4kStarmapFixtures.catalogue(
            P4kStarmapFixtures.object(SYSTEM, "SolarSystem", P4kStarmapFixtures.SYSTEM, null),
            P4kStarmapFixtures.object(ARCCORP, "Planet", "Krt ArcCorp", SYSTEM),
            P4kStarmapFixtures.object(AREA18, "LandingZone", CITY, ARCCORP),
            P4kStarmapFixtures.object(PLAZA, "Manmade", "Krt Plaza", AREA18),
            outpost);
    ArrayNode list = root.putArray("shops");
    for (ObjectNode shop : shops) {
      list.add(shop);
    }
    return P4kTestCatalogues.bytes(root);
  }

  /**
   * The Cubby Blast test shop with an item list.
   *
   * @param place the shop's starmap object, or {@code null}
   * @return the shop record
   */
  private static @NotNull ObjectNode cubby(@Nullable UUID place) {
    return P4kShopFixtures.withItems(
        P4kShopFixtures.shop(KEY, P4kShopFixtures.CUBBY_BLAST, place),
        P4kShopFixtures.HEAVY_ARMOUR);
  }

  /**
   * The catalogue with the Cubby Blast test shop and its item list.
   *
   * @param place the shop's starmap object, or {@code null}
   * @return the plain JSON bytes
   */
  private static byte @NotNull [] catalogue(@Nullable UUID place) {
    return catalogue(cubby(place));
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
   * Reads a test shop as stored.
   *
   * @param key the shop's key
   * @return the shop
   */
  private @NotNull Shop shop(@NotNull String key) {
    entityManager.flush();
    entityManager.clear();
    UUID guid = P4kShopDto.guidOf(key);
    return shopRepository.findAll().stream()
        .filter(s -> guid.equals(s.getP4kUuid()))
        .findFirst()
        .orElseThrow();
  }

  private @NotNull Shop shop() {
    return shop(KEY);
  }

  /**
   * Whether a test shop has an open case of a reason.
   *
   * @param key the shop's key
   * @param reason the reason
   * @return {@code true} when the case exists and is open
   */
  private boolean openCase(@NotNull String key, @NotNull P4kReviewReason reason) {
    return caseRepository
        .findByKindAndGuidAndReason(P4kKind.SHOP, P4kShopDto.guidOf(key), reason)
        .filter(c -> c.getStatus() == P4kReviewStatus.OPEN)
        .isPresent();
  }

  private boolean openCase(@NotNull P4kReviewReason reason) {
    return openCase(KEY, reason);
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
  void aTerminalOfTheSettlementAroundThePlace_isLinked() {
    Terminal cubby = terminal("Cubby Blast - Krt Area18");

    runs.apply(catalogue(PLAZA), false);

    assertThat(shop().getTerminalId()).isEqualTo(cubby.getId());
  }

  @Test
  void aContainerPlacingAnObjectBelowThePlace_findsTheTerminalThere() {
    Outpost outpost = new Outpost();
    outpost.setName(OUTPOST_NAME);
    outpost.setStarSystemName(P4kStarmapFixtures.SYSTEM);
    outpost.setPlanetName("Krt ArcCorp");
    outpostRepository.saveAndFlush(outpost);
    Terminal terminal = new Terminal();
    terminal.setName("Cubby Blast - " + OUTPOST_NAME);
    terminal.setStarSystemName(P4kStarmapFixtures.SYSTEM);
    terminal.setPlanetName("Krt ArcCorp");
    terminal.setOutpostName(OUTPOST_NAME);
    Terminal saved = terminalRepository.saveAndFlush(terminal);
    ObjectNode shop = cubby(ARCCORP);
    shop.putArray("containers").add("OOC_Krtstanton").add(OUTPOST_ENTITY.toUpperCase());

    runs.apply(catalogue(shop), false);

    assertThat(shop().getTerminalId()).isEqualTo(saved.getId());
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

    P4kImportResultDto result = runs.apply(catalogue((UUID) null), false);

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
  void aShopWithNoTerminalAroundItsPlace_isReportedUnmatched_andOpensNoCase() {
    P4kImportResultDto result = runs.apply(catalogue(AREA18), false);

    assertThat(result.shops()).isNotNull();
    assertThat(result.shops().unmatched()).isEqualTo(1);
    assertThat(result.shops().review()).isZero();
    assertThat(openCase(P4kReviewReason.SHOP_LINK_NOT_FOUND)).isFalse();
  }

  @Test
  void aShopWhoseSurroundingTerminalsAreAllTaken_opensNoCase() {
    terminal("Cubby Blast - Krt Area18");
    ObjectNode other =
        P4kShopFixtures.withItems(
            P4kShopFixtures.shop(OTHER_KEY, P4kShopFixtures.DUMPERS_DEPOT, AREA18),
            P4kShopFixtures.HEAVY_ARMOUR);

    runs.apply(catalogue(cubby(AREA18), other), false);

    assertThat(shop(OTHER_KEY).getTerminalId()).isNull();
    assertThat(openCase(OTHER_KEY, P4kReviewReason.SHOP_LINK_NOT_FOUND)).isFalse();
  }

  @Test
  void aShopWithoutAnItemList_opensNoCase() {
    terminal("Dumper's Depot - Krt Area18");

    runs.apply(catalogue(P4kShopFixtures.shop(KEY, P4kShopFixtures.CUBBY_BLAST, AREA18)), false);

    assertThat(openCase(P4kReviewReason.SHOP_LINK_NOT_FOUND)).isFalse();
  }

  @Test
  void aTerminalNamingTheShopByAnAlias_isLinked() {
    Terminal pharmacy = terminal("Medical Shop - Krt Area18");
    ObjectNode shop =
        P4kShopFixtures.withItems(
            P4kShopFixtures.shop(KEY, PHARMACY_REGISTRY, AREA18), P4kShopFixtures.HEAVY_ARMOUR);

    runs.apply(catalogue(shop), false);

    assertThat(shop().getTerminalId()).isEqualTo(pharmacy.getId());
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
  void aRunThatLeavesTheShopOut_leavesItsCaseOpen() {
    terminal("Admin - Krt Area18");
    runs.apply(catalogue(AREA18), false);
    assertThat(openCase(P4kReviewReason.SHOP_LINK_NOT_FOUND)).isTrue();
    ObjectNode partial =
        P4kShopFixtures.withItems(
            P4kShopFixtures.shop(
                "5b000000-0000-4000-8000-0000000000a1,5b000000-0000-4000-8000-0000000000a3",
                P4kShopFixtures.CUBBY_BLAST,
                AREA18),
            P4kShopFixtures.HEAVY_ARMOUR);

    runs.apply(catalogue(partial), false);

    assertThat(openCase(P4kReviewReason.SHOP_LINK_NOT_FOUND)).isTrue();
  }

  @Test
  void aCaseALaterRunNoLongerFinds_isClosedAsResolved() {
    terminal("Admin - Krt Area18");
    runs.apply(catalogue(AREA18), false);
    assertThat(openCase(P4kReviewReason.SHOP_LINK_NOT_FOUND)).isTrue();
    terminal("Cubby Blast - Krt Area18");

    runs.apply(catalogue(AREA18), false);

    assertThat(resolution(P4kReviewReason.SHOP_LINK_NOT_FOUND))
        .isEqualTo(P4kReviewResolution.RESOLVED);
  }

  @Test
  void aCaseTheRulesNoLongerRaise_isClosedAsResolved() {
    terminal("Admin - Krt Area18");
    runs.apply(catalogue(AREA18), false);
    assertThat(openCase(P4kReviewReason.SHOP_LINK_NOT_FOUND)).isTrue();

    runs.apply(catalogue(P4kShopFixtures.shop(KEY, P4kShopFixtures.CUBBY_BLAST, AREA18)), false);

    assertThat(resolution(P4kReviewReason.SHOP_LINK_NOT_FOUND))
        .isEqualTo(P4kReviewResolution.RESOLVED);
  }

  /**
   * Reads the resolution of the test shop's case of a reason.
   *
   * @param reason the reason
   * @return the resolution, or {@code null} while the case is open
   */
  private @Nullable P4kReviewResolution resolution(@NotNull P4kReviewReason reason) {
    return caseRepository
        .findByKindAndGuidAndReason(P4kKind.SHOP, P4kShopDto.guidOf(KEY), reason)
        .orElseThrow()
        .getResolution();
  }
}
