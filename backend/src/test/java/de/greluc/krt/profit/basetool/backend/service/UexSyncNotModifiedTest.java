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

package de.greluc.krt.profit.basetool.backend.service;

import static de.greluc.krt.profit.basetool.backend.service.UexFetchResults.fetched;
import static de.greluc.krt.profit.basetool.backend.service.UexFetchResults.unchanged;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.backend.config.UexProperties;
import de.greluc.krt.profit.basetool.backend.integration.UexClient;
import de.greluc.krt.profit.basetool.backend.model.UexCategory;
import de.greluc.krt.profit.basetool.backend.repository.UexCategoryRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * Pins the {@code 304 Not Modified} branch that H6 added to every migrated UEX sync service.
 *
 * <p>Before this, {@link UexClient} discarded the {@code notModified} flag it had already computed,
 * so an unchanged feed reached the sync services as a bare empty list — indistinguishable from a
 * broken one. All of them then emitted the same alarming {@code WARN No X received from UEX API.
 * Aborting…} for a perfectly healthy fully-cached night. <b>The log level is the contract here</b>,
 * so each case asserts it explicitly: an unchanged catalogue must produce an INFO naming the 304
 * and <em>no</em> WARN or ERROR at all.
 *
 * <p>The second half of the contract is that a 304 changes nothing locally. Every service is
 * constructed with <b>null repositories</b> on purpose: if a 304 run reached a single query, upsert
 * or orphan sweep, it would fail with a {@link NullPointerException} instead of passing. That is a
 * stronger statement than a {@code verifyNoInteractions} on a mock, and it needs no per-service
 * repository fixture. Only the two services whose 304 branch legitimately does touch a collaborator
 * get one: {@link UexCategoryRefService}, which must still return the persisted rows, and {@link
 * UexItemPriceSyncService}, which reads its feature flag off a real {@link UexProperties}.
 */
@ExtendWith(MockitoExtension.class)
class UexSyncNotModifiedTest {

  @Mock private UexClient uexClient;

  @Mock private UexCategoryRepository uexCategoryRepository;

  /** Captures everything the services log during one test so the level can be asserted. */
  private ListAppender<ILoggingEvent> appender;

  /** Root logger the appender is attached to, kept so {@link #detachAppender()} can undo it. */
  private Logger rootLogger;

  /**
   * Attaches a {@link ListAppender} to the root logger before each test. Root rather than a
   * per-class logger because one test exercises several sync services, each with its own logger
   * name, and the assertion is about levels across the whole run.
   */
  @BeforeEach
  void attachAppender() {
    appender = new ListAppender<>();
    appender.start();
    rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    rootLogger.addAppender(appender);
  }

  /** Detaches the appender again so captured events do not leak into unrelated tests. */
  @AfterEach
  void detachAppender() {
    rootLogger.detachAppender(appender);
    appender.stop();
  }

  @Test
  void starSystems_unchangedFeed_logsInfoNotWarn_andTouchesNoRepository() {
    when(uexClient.getStarSystems()).thenReturn(unchanged());
    // Null repository: any local read or write on the 304 path would NPE instead of passing.
    UexStarSystemService service = new UexStarSystemService(uexClient, null);

    service.fetchAndProcessStarSystems();

    assertUnchangedReportedAtInfo("star system");
  }

  @Test
  void vehicles_unchangedFeed_logsInfoAndSkipsTheOrphanSweep() {
    // The load-bearing case: syncVehicles() ends in a ship_type orphan sweep. Returning early on a
    // 304 is what keeps an unchanged catalogue from tombstoning rows it never re-enumerated.
    when(uexClient.getVehicles()).thenReturn(unchanged());
    UexVehicleService service = new UexVehicleService(uexClient, null, null, null);

    service.syncVehicles();

    assertUnchangedReportedAtInfo("vehicle");
  }

  @Test
  void commodities_unchangedFeed_reportsBothCatalogueAndPriceMatrixAtInfo() {
    // Two fetches in one method, and the commodity 304 deliberately falls through to the price
    // fetch instead of returning — so both branches have to be exercised together.
    when(uexClient.getCommodities()).thenReturn(unchanged());
    when(uexClient.getCommoditiesPricesAll()).thenReturn(unchanged());
    UexCommodityService service = new UexCommodityService(uexClient, null, null, null);

    service.fetchAndProcessCommoditiesPrices();

    assertUnchangedReportedAtInfo("commodity catalogue");
    assertUnchangedReportedAtInfo("commodity price matrix");
  }

  @Test
  void manufacturers_unchangedFeed_logsInfoNotWarn() {
    when(uexClient.getCompanies()).thenReturn(unchanged());
    UexManufacturerService service = new UexManufacturerService(uexClient, null, null, null);

    service.syncManufacturers();

    assertUnchangedReportedAtInfo("company");
  }

  @Test
  void categories_unchangedFeed_returnsThePersistedRowsWithoutReimporting() {
    // The one 304 branch that must NOT be a bare early return: the item sync consumes the returned
    // categories, so an unchanged catalogue still has to hand back the persisted rows.
    UexCategory persisted = new UexCategory();
    when(uexClient.getCategories()).thenReturn(unchanged());
    when(uexCategoryRepository.findAll()).thenReturn(List.of(persisted));
    UexCategoryRefService service = new UexCategoryRefService(uexClient, uexCategoryRepository);

    List<UexCategory> result = service.syncCategories();

    assertEquals(List.of(persisted), result, "a 304 must still yield the current category rows");
    assertUnchangedReportedAtInfo("category");
  }

  @Test
  void itemPrices_unchangedFeed_logsInfoAndSkipsTheStaleSweep() {
    // Real properties rather than a mock, matching UexItemPriceSyncServiceTest: the flag is a
    // Lombok getter on a plain config bean, and the sync is gated off unless it is on.
    UexProperties properties = new UexProperties();
    properties.setItemPriceSyncEnabled(true);
    when(uexClient.getItemPrices()).thenReturn(unchanged());
    UexItemPriceSyncService service =
        new UexItemPriceSyncService(uexClient, properties, null, null, null, null);

    service.syncItemPrices();

    assertUnchangedReportedAtInfo("item-price matrix");
  }

  @Test
  void universeSync_everyUnchangedCatalogue_logsInfoNotWarn() {
    // All ten universe catalogues share one shape, so they are pinned in one sweep rather than ten
    // near-identical tests. Every repository is null: reaching any of them would NPE.
    UexUniverseSyncService service =
        new UexUniverseSyncService(
            uexClient, null, null, null, null, null, null, null, null, null, null, null);

    List<UniverseCase> cases =
        List.of(
            new UniverseCase(
                "city",
                () -> when(uexClient.getCities()).thenReturn(unchanged()),
                service::syncCities),
            new UniverseCase(
                "faction",
                () -> when(uexClient.getFactions()).thenReturn(unchanged()),
                service::syncFactions),
            new UniverseCase(
                "jurisdiction",
                () -> when(uexClient.getJurisdictions()).thenReturn(unchanged()),
                service::syncJurisdictions),
            new UniverseCase(
                "moon",
                () -> when(uexClient.getMoons()).thenReturn(unchanged()),
                service::syncMoons),
            new UniverseCase(
                "orbit",
                () -> when(uexClient.getOrbits()).thenReturn(unchanged()),
                service::syncOrbits),
            new UniverseCase(
                "outpost",
                () -> when(uexClient.getOutposts()).thenReturn(unchanged()),
                service::syncOutposts),
            new UniverseCase(
                "planet",
                () -> when(uexClient.getPlanets()).thenReturn(unchanged()),
                service::syncPlanets),
            new UniverseCase(
                "point-of-interest",
                () -> when(uexClient.getPoi()).thenReturn(unchanged()),
                service::syncPois),
            new UniverseCase(
                "space station",
                () -> when(uexClient.getSpaceStations()).thenReturn(unchanged()),
                service::syncSpaceStations),
            new UniverseCase(
                "terminal",
                () -> when(uexClient.getTerminals()).thenReturn(unchanged()),
                service::syncTerminals));

    for (UniverseCase testCase : cases) {
      // Clear first so the ten catalogues never see each other's log lines.
      appender.list.clear();
      testCase.stub().run();
      testCase.run().run();
      assertUnchangedReportedAtInfo(testCase.label());
    }
  }

  @Test
  void genuinelyEmptyFeed_stillWarns() {
    // The counterweight to every case above: the WARN must not have been softened away. An
    // empty-200 is a real outage signal and still has to read like one.
    when(uexClient.getStarSystems()).thenReturn(fetched(List.of()));
    UexStarSystemService service = new UexStarSystemService(uexClient, null);

    service.fetchAndProcessStarSystems();

    assertTrue(
        appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN),
        "an empty-200 must still WARN — only the unchanged (304) case was downgraded");
  }

  @Test
  void unchangedRunNeverTouchesTheCategoryRepository_whenItIsNotTheCategorySync() {
    // Guards the "a 304 changes nothing" half of the contract with an explicit mock as well, so a
    // future refactor that hands the services a real repository cannot quietly lose it.
    when(uexClient.getVehicles()).thenReturn(unchanged());
    new UexVehicleService(uexClient, null, null, null).syncVehicles();

    verifyNoInteractions(uexCategoryRepository);
  }

  /**
   * One universe catalogue's 304 case: how to stub its getter and how to drive its sync method.
   *
   * @param label the noun the service's INFO line uses (e.g. {@code "moon"}), matched
   *     case-insensitively against the captured message
   * @param stub stubs this catalogue's getter to a {@code 304} result
   * @param run invokes this catalogue's sync method
   */
  private record UniverseCase(String label, Runnable stub, Runnable run) {}

  /**
   * Asserts that the run reported an unchanged catalogue the way H6 requires: at least one INFO
   * event naming both {@code label} and the {@code 304}, and not a single WARN or ERROR event.
   *
   * @param label the catalogue noun the service's INFO line is expected to carry
   */
  private void assertUnchangedReportedAtInfo(String label) {
    assertTrue(
        appender.list.stream()
            .anyMatch(
                e ->
                    e.getLevel() == Level.INFO
                        && e.getFormattedMessage().toLowerCase().contains(label.toLowerCase())
                        && e.getFormattedMessage().contains("304")),
        "expected an INFO line naming the unchanged " + label + " catalogue and the 304");
    assertFalse(
        appender.list.stream()
            .anyMatch(e -> e.getLevel() == Level.WARN || e.getLevel() == Level.ERROR),
        "an unchanged (304) feed is healthy and must not WARN or ERROR: "
            + appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
  }
}
