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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.CatalogueMetricNames;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemStatRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiItemDto;
import de.greluc.krt.profit.basetool.backend.livesync.api.AdminDashboardLiveSync;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.platform.api.RestClientConfig;
import de.greluc.krt.profit.basetool.backend.service.RecordingTransactionManager;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/** Verifies real detail transport contributes truthful counters to durable closure-mode history. */
@ExtendWith(MockitoExtension.class)
class ScWikiClientRunHistoryTest {

  private static final UUID ITEM_UUID = UUID.fromString("4577c7a3-76ab-4e37-91de-68862dacb964");
  private static final String GAME_VERSION = "4.10.2-LIVE.12881860";

  @Mock private ExternalSyncRunRepository runs;
  @Mock private ExternalSyncReportRepository reports;
  @Mock private GameItemRepository items;
  @Mock private BlueprintRepository blueprints;
  @Mock private ManufacturerRepository manufacturers;
  @Mock private GameItemStatRepository stats;
  @Mock private ItemReconciliationService reconciliation;
  @Mock private AdminDashboardLiveSync dashboard;
  @Mock private AuditRecorder audit;
  @Mock private LiveSyncPublisher catalogue;
  @Mock private ObjectProvider<ScWikiItemSyncService> self;

  private final SyncRunProgress progress = new SyncRunProgress();
  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  private final Map<UUID, ExternalSyncRun> stored = new LinkedHashMap<>();
  private MockWebServer server;
  private ScWikiClient client;
  private ScWikiItemSyncService itemsSync;
  private SyncRunService history;
  private String itemFixture;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    ScWikiProperties properties =
        BoundProperties.bind(
            ScWikiProperties.class,
            Map.of(
                "api-url",
                server.url("/").toString(),
                "game-version",
                GAME_VERSION,
                "item-sync-enabled",
                true,
                "requests-per-second",
                1000,
                "max-attempts",
                1));
    client =
        new ScWikiClient(
            new RestClientConfig().restClientBuilder(ObservationRegistry.NOOP),
            properties,
            metrics,
            progress);
    client.initClient();
    SyncReportService report =
        new SyncReportService(reports, metrics, dashboard, audit, runs, progress);
    itemsSync =
        new ScWikiItemSyncService(
            client,
            properties,
            items,
            blueprints,
            manufacturers,
            report,
            stats,
            new GameItemStatWriter(stats),
            new ScWikiItemStatFollowUp(client, properties, stats, report),
            reconciliation,
            new ScWikiItemFieldWriter(metrics),
            metrics,
            self);
    history =
        new SyncRunService(
            runs, progress, metrics, dashboard, catalogue, new RecordingTransactionManager());
    try (var resource = getClass().getResourceAsStream("/catalogue/scwiki-item-metadata.json")) {
      assertNotNull(resource);
      itemFixture = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  void closureSuccessPersistsDecodedValidAndCommittedCountsForBothAggregateAndSource() {
    GameItem existing = new GameItem();
    existing.setId(UUID.randomUUID());
    existing.setExternalUuid(ITEM_UUID);
    existing.setName("A03 Akuma");
    existing.setSourceSystems(GameItemSourceSystem.UEX_ONLY);
    when(items.findByExternalUuid(ITEM_UUID)).thenReturn(Optional.of(existing));
    server.enqueue(json(itemFixture));

    runClosure();

    assertEquals(2, stored.size());
    stored
        .values()
        .forEach(run -> {
          assertEquals(SyncRunStatus.SUCCESS, run.getStatus());
          assertEquals(1, run.getFetched());
          assertEquals(1, run.getValid());
          assertEquals(1, run.getApplied());
          assertEquals(0, run.getFailed());
          assertTrue(run.isCensusComplete());
          assertEquals(SyncSweepOutcome.NOT_REQUIRED, run.getSweepOutcome());
        });
    assertEquals("A03 Akuma Sniper Rifle", existing.getScwikiName());
    verify(items).save(existing);
  }

  @Test
  void onlyExplicitNotFoundProducesMissingEvidenceAndOneFailedFetch() {
    server.enqueue(new MockResponse().setResponseCode(404));

    runClosure();

    assertUnavailableHistory(0);
    verify(reports).save(argThat(event -> event.getEventType() == SyncEventType.WIKI_MISSING));
    verify(items, never()).save(any());
  }

  @Test
  void unavailableDetailsArePartialWithoutFalseMissingEvidence() {
    server.enqueue(new MockResponse().setResponseCode(503));
    runClosure();
    assertUnavailableHistory(0);
    assertEquals(1.0, fetchErrors());
    verify(reports, never()).save(any());
  }

  @Test
  void malformedSuccessfulDetailsAreUnavailableRatherThanMissing() {
    server.enqueue(json("{broken"));

    runClosure();

    assertUnavailableHistory(0);
    assertEquals(1.0, fetchErrors());
    verify(reports, never()).save(any());
  }

  @Test
  void bodylessSuccessDoesNotMasqueradeAsHttpNotFound() {
    server.enqueue(new MockResponse().setResponseCode(200));

    runClosure();

    assertUnavailableHistory(0);
    verify(reports, never()).save(any());
  }

  @Test
  void conflictingDetailUuidIsRejectedBeforeTheWriterAndCountedOnce() {
    server.enqueue(json(itemFixture.replace(ITEM_UUID.toString(), UUID.randomUUID().toString())));

    runClosure();

    assertUnavailableHistory(1);
    verify(reports)
        .save(argThat(event -> event.getEventType() == SyncEventType.SOURCE_IDENTITY_CONFLICT));
    verify(items, never()).findByExternalUuid(any());
    verify(items, never()).save(any());
  }

  @Test
  void missingDetailUuidCannotBeAppliedToTheRequestedAsset() {
    server.enqueue(json("{\"data\":{\"name\":\"A03 Akuma\"}}"));

    runClosure();

    assertUnavailableHistory(1);
    verify(reports)
        .save(argThat(event -> event.getEventType() == SyncEventType.SOURCE_IDENTITY_CONFLICT));
    verify(items, never()).findByExternalUuid(any());
  }

  @Test
  void slugRequestsRetainTheirNonUuidDetailContract() {
    server.enqueue(json("{\"data\":{\"name\":\"A03 Akuma\"}}"));

    ScWikiClient.DetailResult<ScWikiItemDto> result =
        client.fetchOneResult("/api/items", "a03-akuma", ScWikiItemDto.class, "item");

    assertEquals(ScWikiClient.DetailOutcome.FOUND, result.outcome());
    assertNotNull(result.data());
    assertEquals("A03 Akuma", result.data().name());
  }

  /**
   * Runs the real closure writer inside its independently persisted aggregate and source records.
   */
  private void runClosure() {
    when(runs.saveAndFlush(any()))
        .thenAnswer(invocation -> {
          ExternalSyncRun run = invocation.getArgument(0);
          stored.put(run.getId(), run);
          return run;
        });
    when(runs.findById(any()))
        .thenAnswer(invocation -> Optional.ofNullable(stored.get(invocation.getArgument(0))));
    when(items.findAllExternalUuids()).thenReturn(List.of(ITEM_UUID));
    when(blueprints.findReferencedItemUuids()).thenReturn(List.of());
    lenient().when(self.getObject()).thenReturn(itemsSync);
    history.run(
        SyncSourceSystem.SCWIKI,
        SyncAggregate.CATALOGUE,
        GAME_VERSION,
        () ->
            history
                .run(
                    SyncSourceSystem.SCWIKI,
                    SyncAggregate.GAME_ITEM,
                    GAME_VERSION,
                    itemsSync::syncItems)
                .count());
  }

  /** Asserts the rejected delivery and its containing source retain exactly one handled failure. */
  private void assertUnavailableHistory(long decodedRows) {
    assertEquals(2, stored.size());
    stored
        .values()
        .forEach(run -> {
          assertEquals(SyncRunStatus.PARTIAL, run.getStatus());
          assertEquals(decodedRows, run.getFetched());
          assertEquals(0, run.getValid());
          assertEquals(0, run.getApplied());
          assertEquals(1, run.getFailed());
          assertFalse(run.isCensusComplete());
        });
    assertEquals(1.0, fetchErrors());
  }

  /** Returns the single-call failure metric across its bounded reason labels. */
  private double fetchErrors() {
    return metrics.find(CatalogueMetricNames.EXTERNAL_FETCH_ERRORS).counters().stream()
        .mapToDouble(counter -> counter.count())
        .sum();
  }

  /** Builds an offline JSON response served by the loopback upstream. */
  private static MockResponse json(String payload) {
    return new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(payload);
  }
}
