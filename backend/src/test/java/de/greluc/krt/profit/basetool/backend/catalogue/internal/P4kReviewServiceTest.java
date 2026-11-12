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
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import de.greluc.krt.profit.basetool.backend.livesync.api.LivesyncMetricNames;
import de.greluc.krt.profit.basetool.backend.platform.api.CacheConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;
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
  @Autowired private P4kReviewWorkspaceService workspace;
  @Autowired private GameItemRepository gameItems;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private P4kReviewCaseRepository caseRepository;
  @Autowired private P4kImportChangeRepository changeRepository;
  @Autowired private AuditService auditService;
  @Autowired private CacheManager cacheManager;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private SyncCoordinator syncCoordinator;

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
      assertThat(P4kImportRuns.report(changeRepository, run))
          .filteredOn(c -> c.getChangeType() == P4kChangeType.REVIEW)
          .extracting(P4kImportChange::getGuid)
          .containsOnlyOnce(P4kTestCatalogues.AI_SHIP);
    }
  }

  @Test
  void aCaseAnAdminClosed_isNeitherCountedNorReportedByALaterRun() {
    P4kImportResultDto first = runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    P4kReviewCase reviewCase = seededCase();
    reviewService.resolve(
        reviewCase.getId(), P4kReviewResolution.KEEP, null, null, reviewCase.getVersion(), ADMIN);

    P4kImportResultDto second = runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    P4kImportService.P4kPreview preview = runs.previewWithReport(P4kTestCatalogues.fixtureBytes());

    assertThat(second.ships().review()).isEqualTo(first.ships().review() - 1);
    assertThat(P4kImportRuns.report(changeRepository, second.runId()))
        .filteredOn(c -> c.getChangeType() == P4kChangeType.REVIEW)
        .extracting(P4kImportChange::getGuid)
        .doesNotContain(P4kTestCatalogues.AI_SHIP);
    assertThat(preview.changes())
        .filteredOn(c -> c.getChangeType() == P4kChangeType.REVIEW)
        .extracting(P4kImportChange::getGuid)
        .doesNotContain(P4kTestCatalogues.AI_SHIP);
    assertThat(seededCase().getLastRunId()).isEqualTo(first.runId());
    assertThat(seededCase().getStatus()).isEqualTo(P4kReviewStatus.REVIEWED);
  }

  @Test
  void aResolutionWhileAnApplyHoldsTheSyncLock_isAConflict_andWritesNothing() throws Exception {
    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    P4kReviewCase reviewCase = seededCase();
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread apply =
        Thread.ofVirtual()
            .start(() ->
                syncCoordinator.runExclusively(
                    P4kImportJobRunner.APPLY_LOCK_LABEL,
                    () -> {
                      held.countDown();
                      awaitQuietly(release);
                    }));
    try {
      assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(() ->
              reviewService.resolve(
                  reviewCase.getId(),
                  P4kReviewResolution.HIDE,
                  null,
                  null,
                  reviewCase.getVersion(),
                  ADMIN))
          .isInstanceOf(BusinessConflictException.class);
    } finally {
      release.countDown();
      apply.join();
    }
    assertThat(seededCase().getStatus()).isEqualTo(P4kReviewStatus.OPEN);
    assertThat(seeded.getP4kHiddenAt()).isNull();
  }

  /**
   * Waits for a latch, keeping the thread's interrupt flag.
   *
   * @param latch the latch
   */
  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
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

  @Test
  void attachesExactWikiItemAndRejectsAnOutdatedCandidate() {
    UUID run = runs.apply(P4kTestCatalogues.fixtureBytes(), false).runId();
    UUID guid = UUID.randomUUID();
    GameItem item = new GameItem();
    item.setName("Review optic");
    item.setClassName("Review_Optic_01");
    item.setExternalUuid(guid);
    item.setScwikiSlug("review-optic");
    item.setKind(GameItemKind.GENERIC);
    item.setSourceSystems(GameItemSourceSystem.WIKI_ONLY);
    gameItems.saveAndFlush(item);
    P4kReviewCase review =
        openWorkspaceCase(
            run,
            guid,
            item.getId(),
            "Review optic",
            "Review_Optic_01",
            "game/optics/review_optic_01");

    P4kReviewCandidate candidate =
        workspace.detail(review.getId()).options().candidates().stream()
            .filter(option -> option.action() == P4kReviewAction.ATTACH_P4K)
            .findFirst()
            .orElseThrow();

    var stale =
        new P4kReviewApplyRequest(
            candidate.action(),
            candidate.targetId(),
            candidate.targetVersion() + 1,
            candidate.subjectVersion(),
            review.getVersion());
    assertThatThrownBy(() -> workspace.applyWithinLock(review.getId(), stale, ADMIN))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    assertThat(item.getP4kUuid()).isNull();
  }

  @Test
  void appliesExactWikiItemIdentityAndAuditsTheDecision() {
    UUID run = runs.apply(P4kTestCatalogues.fixtureBytes(), false).runId();
    UUID guid = UUID.randomUUID();
    GameItem item = new GameItem();
    item.setName("Review compass");
    item.setClassName("Review_Compass_01");
    item.setExternalUuid(guid);
    item.setScwikiSlug("review-compass");
    item.setKind(GameItemKind.GENERIC);
    item.setSourceSystems(GameItemSourceSystem.WIKI_ONLY);
    gameItems.saveAndFlush(item);
    P4kReviewCase review =
        openWorkspaceCase(
            run,
            guid,
            item.getId(),
            "Review compass",
            "Review_Compass_01",
            "game/optics/review_compass_01");
    P4kReviewCandidate candidate =
        workspace.detail(review.getId()).options().candidates().stream()
            .filter(option -> option.action() == P4kReviewAction.ATTACH_P4K)
            .findFirst()
            .orElseThrow();

    P4kReviewCaseDto applied =
        workspace.apply(
            review.getId(),
            new P4kReviewApplyRequest(
                candidate.action(),
                candidate.targetId(),
                candidate.targetVersion(),
                candidate.subjectVersion(),
                review.getVersion()),
            ADMIN);

    assertThat(applied.status()).isEqualTo(P4kReviewStatus.REVIEWED);
    assertThat(applied.resolution()).isEqualTo(P4kReviewResolution.APPLIED);
    assertThat(item.getP4kUuid()).isEqualTo(guid);
    assertThat(item.getScwikiSlug()).isEqualTo("review-compass");
    assertThat(events(AuditEventType.CATALOGUE_P4K_REVIEW_APPLIED))
        .anySatisfy(event -> assertThat(event.subjectId()).isEqualTo(review.getId()));
  }

  @Test
  void aBatchReviewsOnlyTheSelectedMembers() {
    UUID run = runs.apply(P4kTestCatalogues.fixtureBytes(), false).runId();
    P4kReviewCase first =
        openWorkspaceCase(
            run, UUID.randomUUID(), null, "Door", "Review_Door_01", "game/doors/first");
    P4kReviewCase second =
        openWorkspaceCase(
            run, UUID.randomUUID(), null, "Door", "Review_Door_02", "game/doors/second");
    P4kReviewGroupDto group =
        workspace
            .groups(P4kKind.ITEM, P4kReviewReason.GUID_CONFLICT, null, PageRequest.of(0, 20))
            .getContent()
            .stream()
            .filter(entry -> entry.name().equals("Door"))
            .findFirst()
            .orElseThrow();
    assertThat(group.batchable()).isTrue();

    workspace.resolveBatch(
        group.id(),
        new P4kReviewBatchRequest(
            P4kReviewResolution.KEEP,
            List.of(new P4kReviewBatchMember(first.getId(), first.getVersion()))),
        ADMIN);

    assertThat(first.getStatus()).isEqualTo(P4kReviewStatus.REVIEWED);
    assertThat(second.getStatus()).isEqualTo(P4kReviewStatus.OPEN);
  }

  private P4kReviewCase openWorkspaceCase(
      UUID run, UUID guid, @Nullable UUID rowId, String name, String className, String recordPath) {
    P4kImportChange change = new P4kImportChange();
    change.setRunId(run);
    change.setKind(P4kKind.ITEM);
    change.setGuid(guid);
    change.setChangeType(P4kChangeType.REVIEW);
    change.setFieldName(P4kReviewReason.GUID_CONFLICT.name());
    change.setNameEn(name);
    change.setClassName(className);
    change.setRecordPath(recordPath);
    changeRepository.saveAndFlush(change);

    P4kReviewCase review = new P4kReviewCase();
    review.setKind(P4kKind.ITEM);
    review.setGuid(guid);
    review.setRowId(rowId);
    review.setReason(P4kReviewReason.GUID_CONFLICT);
    review.setFirstRunId(run);
    review.setLastRunId(run);
    return caseRepository.saveAndFlush(review);
  }
}
