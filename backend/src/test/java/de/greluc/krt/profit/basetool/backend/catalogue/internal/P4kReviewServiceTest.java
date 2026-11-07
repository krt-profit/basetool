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
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventDto;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditService;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import de.greluc.krt.profit.basetool.backend.livesync.api.LivesyncMetricNames;
import de.greluc.krt.profit.basetool.backend.platform.api.CacheConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A review case is a lasting status, resolved as keep or hide by an admin, audited, and a hide
 * outlives every later match (REQ-P4K-016).
 */
@SpringBootTest
@Transactional
class P4kReviewServiceTest {

  /** The admin who resolves the cases of these tests. */
  private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-00000000a4d2");

  @Autowired private P4kImportService importService;
  @Autowired private P4kReviewService reviewService;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private P4kReviewCaseRepository caseRepository;
  @Autowired private P4kImportChangeRepository changeRepository;
  @Autowired private AuditService auditService;
  @Autowired private CacheManager cacheManager;
  @Autowired private MeterRegistry meterRegistry;

  /** The key of the entry that shows whether a cache was evicted. */
  private static final String CACHE_PROBE = "p4k-review-probe";

  private P4kImportRuns runs;
  private ShipType seeded;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(importService, jobRepository);
    seeded = new ShipType();
    seeded.setName("Review seeded AI Hornet");
    seeded.setClassName(P4kTestCatalogues.AI_SHIP_CLASS);
    seeded.setSourceSystems(GameItemSourceSystem.P4K);
    shipTypeRepository.save(seeded);
  }

  private P4kReviewCase seededCase() {
    return caseRepository
        .findByKindAndGuidAndReason(
            P4kKind.SHIP, P4kTestCatalogues.AI_SHIP, P4kReviewReason.SEEDED_NOT_CREATABLE)
        .orElseThrow();
  }

  private List<AuditEventDto> events(AuditEventType type) {
    return auditService
        .getEvents(AuditDomain.CATALOGUE, null, null, null, type, null, PageRequest.of(0, 50))
        .getContent();
  }

  @Test
  void aCaseFoundByTwoRuns_isOneOpenCase_loggedAsReviewInBothReports() {
    UUID first = runs.apply(P4kTestCatalogues.fixtureBytes(), false).runId();
    UUID second = runs.apply(P4kTestCatalogues.fixtureBytes(), false).runId();

    P4kReviewCase reviewCase = seededCase();
    assertThat(reviewCase.getStatus()).isEqualTo(P4kReviewStatus.OPEN);
    assertThat(reviewService.openCases(PageRequest.of(0, 500)).getContent())
        .filteredOn(c -> c.id().equals(reviewCase.getId()))
        .singleElement()
        .satisfies(c -> {
          assertThat(c.rowName()).isEqualTo("Review seeded AI Hornet");
          assertThat(c.className()).isEqualTo(P4kTestCatalogues.AI_SHIP_CLASS);
          assertThat(c.lastRunId()).isEqualTo(second);
        });
    for (UUID run : List.of(first, second)) {
      assertThat(changeRepository.findByRunId(run))
          .filteredOn(c -> c.getChangeType() == P4kChangeType.REVIEW)
          .extracting(P4kImportChange::getGuid)
          .containsOnlyOnce(P4kTestCatalogues.AI_SHIP);
    }
  }

  @Test
  void hide_retiresTheRow_andTheNextApplyThatMatchesItLeavesItHidden() {
    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    P4kReviewCase reviewCase = seededCase();

    P4kReviewCaseDto resolved =
        reviewService.resolve(
            reviewCase.getId(),
            P4kReviewResolution.HIDE,
            null,
            null,
            reviewCase.getVersion(),
            ADMIN);
    runs.apply(P4kTestCatalogues.fixtureBytes(), true);

    assertThat(resolved.status()).isEqualTo(P4kReviewStatus.REVIEWED);
    assertThat(seeded.getP4kHiddenAt()).isNotNull();
    assertThat(seeded.isP4kRetired()).isTrue();
    assertThat(seededCase().getStatus()).isEqualTo(P4kReviewStatus.REVIEWED);
    assertThat(reviewService.openCases(PageRequest.of(0, 500)).getContent())
        .noneMatch(c -> c.id().equals(reviewCase.getId()));
  }

  @Test
  void aResolution_storesTheAdminAndTheTime_andWritesOneAuditEvent() {
    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    P4kReviewCase reviewCase = seededCase();
    int before = events(AuditEventType.CATALOGUE_P4K_REVIEW_KEPT).size();

    reviewService.resolve(
        reviewCase.getId(), P4kReviewResolution.KEEP, null, null, reviewCase.getVersion(), ADMIN);

    P4kReviewCase stored = seededCase();
    assertThat(stored.getResolution()).isEqualTo(P4kReviewResolution.KEEP);
    assertThat(stored.getResolvedBy()).isEqualTo(ADMIN);
    assertThat(stored.getResolvedAt()).isNotNull();
    assertThat(seeded.getP4kHiddenAt()).isNull();
    assertThat(events(AuditEventType.CATALOGUE_P4K_REVIEW_KEPT))
        .hasSize(before + 1)
        .filteredOn(e -> reviewCase.getId().equals(e.subjectId()))
        .singleElement()
        .satisfies(e ->
            assertThat(e.details())
                .isEqualTo("kind=SHIP reason=SEEDED_NOT_CREATABLE rowId=" + seeded.getId()));
  }

  @Test
  void aHide_evictsTheCatalogueCachesOnCommit() {
    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    P4kReviewCase reviewCase = seededCase();
    Cache shipTypes = warmShipTypeCache();

    onCommitOf(() ->
        reviewService.resolve(
            reviewCase.getId(),
            P4kReviewResolution.HIDE,
            null,
            null,
            reviewCase.getVersion(),
            ADMIN));

    assertThat(shipTypes.get(CACHE_PROBE)).isNull();
  }

  @Test
  void aKeep_leavesTheCatalogueCachesWarm() {
    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    P4kReviewCase reviewCase = seededCase();
    Cache shipTypes = warmShipTypeCache();

    onCommitOf(() ->
        reviewService.resolve(
            reviewCase.getId(),
            P4kReviewResolution.KEEP,
            null,
            null,
            reviewCase.getVersion(),
            ADMIN));

    assertThat(shipTypes.get(CACHE_PROBE)).isNotNull();
  }

  @Test
  void aKeep_tellsTheOtherAdminPagesOnCommit() {
    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    P4kReviewCase reviewCase = seededCase();
    double before = adminRoomFrames();

    onEveryCommitCallback(() ->
        reviewService.resolve(
            reviewCase.getId(),
            P4kReviewResolution.KEEP,
            null,
            null,
            reviewCase.getVersion(),
            ADMIN));

    assertThat(adminRoomFrames()).isEqualTo(before + 1);
  }

  /**
   * Runs a write and then every commit callback the test's transaction holds, as its commit would.
   *
   * @param write the write
   */
  private static void onEveryCommitCallback(Runnable write) {
    write.run();
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
  }

  /**
   * Counts the frames published into the admin catalogue room.
   *
   * @return the accepted publishes of the room so far
   */
  private double adminRoomFrames() {
    return meterRegistry
        .counter(
            LivesyncMetricNames.LIVESYNC_PUBLISH_ACCEPTED,
            LivesyncMetricNames.TAG_TOPIC_CLASS,
            LiveSyncTopicClass.CATALOGUE_ADMIN.metricLabel())
        .count();
  }

  /**
   * Puts a probe entry into the ship-type cache.
   *
   * @return the cache
   */
  private Cache warmShipTypeCache() {
    Cache cache = Objects.requireNonNull(cacheManager.getCache(CacheConfig.SHIP_TYPES_CACHE));
    cache.put(CACHE_PROBE, "warm");
    return cache;
  }

  /**
   * Runs a write and then the commit callbacks it registered, as the commit of the test's
   * rolled-back transaction would.
   *
   * @param write the write
   */
  private static void onCommitOf(Runnable write) {
    List<TransactionSynchronization> before =
        TransactionSynchronizationManager.getSynchronizations();
    write.run();
    TransactionSynchronizationManager.getSynchronizations().stream()
        .filter(s -> !before.contains(s))
        .forEach(TransactionSynchronization::afterCommit);
  }

  @Test
  void aStaleVersion_isAConflict_andAResolvedCaseCannotBeResolvedAgain() {
    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    P4kReviewCase reviewCase = seededCase();
    long version = reviewCase.getVersion();

    assertThatThrownBy(() ->
            reviewService.resolve(
                reviewCase.getId(), P4kReviewResolution.KEEP, null, null, version + 1, ADMIN))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    P4kReviewCaseDto kept =
        reviewService.resolve(
            reviewCase.getId(), P4kReviewResolution.KEEP, null, null, version, ADMIN);
    assertThatThrownBy(() ->
            reviewService.resolve(
                reviewCase.getId(), P4kReviewResolution.HIDE, null, null, kept.version(), ADMIN))
        .isInstanceOf(BadRequestException.class);
  }
}
