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
import static org.awaitility.Awaitility.await;

import de.greluc.krt.profit.basetool.backend.catalogue.api.CatalogueMetricNames;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialExternalAliasService;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.platform.api.RestClientConfig;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

/** Verifies complete source reads cannot advance application freshness when cleanup is refused. */
@SpringBootTest
class ScWikiApplyRefusalIntegrationTest {

  private static final String GAME_VERSION = "4.10.2-LIVE.12881860";
  private static final String EMPTY_CENSUS =
      "{\"data\":[],\"meta\":{\"current_page\":1,\"last_page\":1,\"total\":0}}";

  @Autowired private MaterialRepository materials;
  @Autowired private ManufacturerRepository manufacturers;
  @Autowired private MaterialExternalAliasService aliases;
  @Autowired private SyncChunkWriter writer;
  @Autowired private SyncRunProgress progress;
  @Autowired private SyncRunService runs;
  @Autowired private SyncReportService reports;
  @Autowired private ExternalSyncRunRepository attempts;
  @Autowired private MeterRegistry metrics;

  private final List<UUID> attemptIds = new ArrayList<>();
  private final List<UUID> materialIds = new ArrayList<>();
  private final List<UUID> manufacturerIds = new ArrayList<>();
  private MockWebServer upstream;
  private ScWikiCommoditySyncService commoditySync;
  private ScWikiManufacturerSyncService manufacturerSync;

  @BeforeEach
  void setUp() throws Exception {
    upstream = new MockWebServer();
    upstream.start();
    ScWikiProperties properties =
        BoundProperties.bind(
            ScWikiProperties.class,
            Map.of(
                "api-url",
                upstream.url("/").toString(),
                "game-version",
                GAME_VERSION,
                "commodity-sync-enabled",
                true,
                "manufacturer-sync-enabled",
                true,
                "max-attempts",
                1));
    ScWikiClient client =
        new ScWikiClient(
            new RestClientConfig().restClientBuilder(ObservationRegistry.NOOP),
            properties,
            metrics,
            progress);
    client.initClient();
    commoditySync =
        new ScWikiCommoditySyncService(client, properties, materials, aliases, reports, writer);
    manufacturerSync =
        new ScWikiManufacturerSyncService(client, properties, manufacturers, reports, writer);
  }

  @AfterEach
  void cleanUp() throws Exception {
    try {
      writer.runInNewTransaction(() -> {
        materials.deleteAllById(materialIds);
        manufacturers.deleteAllById(manufacturerIds);
        attempts.deleteAllById(attemptIds);
      });
    } finally {
      upstream.shutdown();
    }
  }

  @Test
  void completeEmptyCommodityCensusRetainsLinkedRowsAndTheLastCompleteApplication()
      throws Exception {
    Material material = new Material();
    material.setName("Refused commodity " + UUID.randomUUID());
    material.setType(MaterialType.NO_REFINE);
    material.setScwikiUuid(UUID.randomUUID());
    material.setScwikiSyncedAt(Instant.parse("2026-01-01T00:00:00Z"));
    AttemptPair baseline =
        successfulSeed(
            SyncAggregate.COMMODITY,
            () -> {
              Material saved = materials.saveAndFlush(material);
              materialIds.add(saved.getId());
            });
    Material before = materials.findById(material.getId()).orElseThrow();
    waitBeyondFreshnessSecond(baseline);
    upstream.enqueue(json(EMPTY_CENSUS));

    AttemptPair refused = tracked(SyncAggregate.COMMODITY, commoditySync::syncCommodities);

    assertRefused(refused, 0, 0, 0);
    assertPriorFreshness(baseline, SyncAggregate.COMMODITY);
    Material after = materials.findById(material.getId()).orElseThrow();
    assertThat(after.getScwikiDeletedAt()).isNull();
    assertThat(after.getScwikiUuid()).isEqualTo(before.getScwikiUuid());
    assertThat(after.getVersion()).isEqualTo(before.getVersion());
    assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
    assertPinnedRequest();
  }

  @Test
  void completeEmptyManufacturerCensusRetainsLinkedRowsAndTheLastCompleteApplication()
      throws Exception {
    Manufacturer manufacturer = linkedManufacturer();
    AttemptPair baseline =
        successfulSeed(
            SyncAggregate.MANUFACTURER,
            () -> {
              Manufacturer saved = manufacturers.saveAndFlush(manufacturer);
              manufacturerIds.add(saved.getId());
            });
    Manufacturer before = manufacturers.findById(manufacturer.getId()).orElseThrow();
    waitBeyondFreshnessSecond(baseline);
    upstream.enqueue(json(EMPTY_CENSUS));

    AttemptPair refused = tracked(SyncAggregate.MANUFACTURER, manufacturerSync::syncManufacturers);

    assertRefused(refused, 0, 0, 0);
    assertPriorFreshness(baseline, SyncAggregate.MANUFACTURER);
    assertRetainedManufacturer(before);
    assertPinnedRequest();
  }

  @Test
  void completeButWhollyUnmatchedManufacturerCensusIsASkippedApplication() throws Exception {
    Manufacturer manufacturer = linkedManufacturer();
    AttemptPair baseline =
        successfulSeed(
            SyncAggregate.MANUFACTURER,
            () -> {
              Manufacturer saved = manufacturers.saveAndFlush(manufacturer);
              manufacturerIds.add(saved.getId());
            });
    Manufacturer before = manufacturers.findById(manufacturer.getId()).orElseThrow();
    waitBeyondFreshnessSecond(baseline);
    UUID unmatched = UUID.randomUUID();
    upstream.enqueue(
        json(
            """
            {"data":[{"uuid":"%s","name":"Unmatched %s","code":"%s"}],
             "meta":{"current_page":1,"last_page":1,"total":1}}
            """
                .formatted(unmatched, unmatched, unmatched)));

    AttemptPair refused = tracked(SyncAggregate.MANUFACTURER, manufacturerSync::syncManufacturers);

    assertRefused(refused, 1, 1, 1);
    assertPriorFreshness(baseline, SyncAggregate.MANUFACTURER);
    assertRetainedManufacturer(before);
    assertThat(manufacturers.findByScwikiUuid(unmatched)).isEmpty();
    assertPinnedRequest();
  }

  /** Seeds a committed catalogue row inside a genuine complete source and child attempt. */
  private AttemptPair successfulSeed(SyncAggregate aggregate, Runnable seed) {
    AttemptPair baseline =
        tracked(
            aggregate,
            () -> {
              writer.runInNewTransaction(seed);
              progress.fetched(1, 1, true);
              progress.applied(1);
              return 1;
            });
    assertThat(baseline.source().getStatus()).isEqualTo(SyncRunStatus.SUCCESS);
    assertThat(baseline.child().getStatus()).isEqualTo(SyncRunStatus.SUCCESS);
    return baseline;
  }

  /** Persists a nested application attempt using the same real progress collector as the client. */
  private AttemptPair tracked(SyncAggregate aggregate, IntSupplier work) {
    List<UUID> ids = new ArrayList<>();
    runs.run(
        SyncSourceSystem.SCWIKI,
        SyncAggregate.CATALOGUE,
        GAME_VERSION,
        () -> {
          ids.add(reports.beginRun());
          attemptIds.add(ids.getLast());
          return runs.run(
                  SyncSourceSystem.SCWIKI,
                  aggregate,
                  GAME_VERSION,
                  () -> {
                    ids.add(reports.beginRun());
                    attemptIds.add(ids.getLast());
                    return work.getAsInt();
                  })
              .count();
        });
    return new AttemptPair(
        attempts.findById(ids.getFirst()).orElseThrow(),
        attempts.findById(ids.getLast()).orElseThrow());
  }

  /**
   * Verifies the complete transport census remains distinct from a refused database application.
   */
  private void assertRefused(AttemptPair pair, long fetched, long valid, long skipped) {
    for (ExternalSyncRun attempt : List.of(pair.source(), pair.child())) {
      assertThat(attempt.getStatus()).isEqualTo(SyncRunStatus.PARTIAL);
      assertThat(attempt.isCensusComplete()).isTrue();
      assertThat(attempt.getSweepOutcome()).isEqualTo(SyncSweepOutcome.SKIPPED);
      assertThat(attempt.getFetched()).isEqualTo(fetched);
      assertThat(attempt.getValid()).isEqualTo(valid);
      assertThat(attempt.getSkipped()).isEqualTo(skipped);
      assertThat(attempt.getApplied()).isZero();
      assertThat(attempt.getFailed()).isZero();
    }
  }

  /** Keeps the refusal after the baseline gauge's second so an accidental advance is observable. */
  private void waitBeyondFreshnessSecond(AttemptPair baseline) {
    long previous = baseline.source().getFinishedAt().getEpochSecond();
    await().atMost(Duration.ofSeconds(3)).until(() -> Instant.now().getEpochSecond() > previous);
  }

  /** Checks both durable last-complete selection and the gauges retain the committed baseline. */
  private void assertPriorFreshness(AttemptPair baseline, SyncAggregate child) {
    for (ExternalSyncRun previous : List.of(baseline.source(), baseline.child())) {
      List<ExternalSyncRun> complete =
          attempts.findBySourceSystemAndAggregateAndStatusInOrderByFinishedAtDesc(
              SyncSourceSystem.SCWIKI,
              previous.getAggregate(),
              List.of(SyncRunStatus.SUCCESS, SyncRunStatus.UNCHANGED),
              PageRequest.of(0, 1));
      assertThat(complete)
          .singleElement()
          .extracting(ExternalSyncRun::getId)
          .isEqualTo(previous.getId());
      double lastComplete =
          metrics
              .get(CatalogueMetricNames.CATALOGUE_ATTEMPT_LAST_COMPLETE)
              .tags("source", "SCWIKI", "aggregate", previous.getAggregate().name())
              .gauge()
              .value();
      assertThat(lastComplete).isEqualTo(previous.getFinishedAt().getEpochSecond());
    }
    assertThat(baseline.child().getAggregate()).isEqualTo(child);
  }

  /** Verifies the unmatched source row did not tombstone or update the existing identity. */
  private void assertRetainedManufacturer(Manufacturer before) {
    Manufacturer after = manufacturers.findById(before.getId()).orElseThrow();
    assertThat(after.getScwikiDeletedAt()).isNull();
    assertThat(after.getScwikiUuid()).isEqualTo(before.getScwikiUuid());
    assertThat(after.getVersion()).isEqualTo(before.getVersion());
    assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
  }

  /** Confirms the real client used its loopback endpoint and the fixed source patch. */
  private void assertPinnedRequest() throws Exception {
    var request = upstream.takeRequest();
    assertThat(request.getRequestUrl()).isNotNull();
    assertThat(request.getRequestUrl().queryParameter("version")).isEqualTo(GAME_VERSION);
    assertThat(upstream.getRequestCount()).isEqualTo(1);
  }

  /** Creates an existing Wiki-linked manufacturer that must survive an apply refusal. */
  private static Manufacturer linkedManufacturer() {
    Manufacturer manufacturer = new Manufacturer();
    manufacturer.setName("Refused manufacturer " + UUID.randomUUID());
    manufacturer.setAbbreviation("REFUSAL");
    manufacturer.setScwikiUuid(UUID.randomUUID());
    manufacturer.setScwikiSyncedAt(Instant.parse("2026-01-01T00:00:00Z"));
    return manufacturer;
  }

  /** Builds a JSON body for the test's loopback source. */
  private static MockResponse json(String body) {
    return new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body);
  }

  /** The persisted umbrella attempt and its independently recorded catalogue slice. */
  private record AttemptPair(ExternalSyncRun source, ExternalSyncRun child) {}
}
