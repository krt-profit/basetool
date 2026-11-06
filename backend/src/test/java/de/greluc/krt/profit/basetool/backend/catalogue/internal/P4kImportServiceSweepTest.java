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
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventDto;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditService;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * The orphan sweep acts only on a complete kind with a valid census, stamps only P4K-known rows the
 * catalogue no longer lists, and needs the admin's override above 5 % of a kind (REQ-P4K-006,
 * REQ-P4K-011, REQ-P4K-012).
 */
@SpringBootTest
@Transactional
class P4kImportServiceSweepTest {

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private P4kImportChangeRepository changeRepository;
  @Autowired private AuditService auditService;

  private P4kImportRuns runs;
  private P4kSweepFixtures fixtures;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
    fixtures = new P4kSweepFixtures(gameItemRepository, shipTypeRepository);
  }

  /**
   * A catalogue whose items are exactly the listed GUIDs.
   *
   * @param listed the item GUIDs the catalogue lists
   * @return the catalogue tree
   */
  private static ObjectNode itemsListing(List<UUID> listed) {
    ObjectNode root = P4kTestCatalogues.tree();
    P4kSweepFixtures.listOnly(root, "items", P4kTestCatalogues.SHIELD, listed);
    return root;
  }

  private List<P4kImportChange> changes(UUID runId, P4kChangeType type) {
    return changeRepository.findByRunId(runId).stream()
        .filter(c -> c.getChangeType() == type)
        .toList();
  }

  private static P4kImportResultDto.Sweep sweep(P4kImportResultDto result, P4kKind kind) {
    return result.sweeps().stream().filter(s -> s.kind() == kind).findFirst().orElseThrow();
  }

  private List<AuditEventDto> overrideEvents() {
    return auditService
        .getEvents(
            AuditDomain.CATALOGUE,
            null,
            null,
            null,
            AuditEventType.CATALOGUE_P4K_MASS_GUARD_OVERRIDDEN,
            null,
            PageRequest.of(0, 50))
        .getContent();
  }

  @Test
  void aCompleteKind_stampsAKnownRowItNoLongerLists_andLeavesAUexOnlyRowAlone() {
    List<UUID> listed = P4kSweepFixtures.guids(19);
    List<GameItem> kept = fixtures.knownItems(listed);
    GameItem gone = fixtures.knownItem(UUID.randomUUID());
    GameItem uexOnly = new GameItem();
    uexOnly.setName("UEX-only sweep item");
    uexOnly.setUexItemId(987_654);
    gameItemRepository.save(uexOnly);

    P4kImportResultDto result = runs.apply(P4kTestCatalogues.bytes(itemsListing(listed)), false);

    assertThat(gone.getP4kDeletedAt()).isNotNull();
    assertThat(uexOnly.getP4kDeletedAt()).isNull();
    assertThat(kept).allSatisfy(row -> assertThat(row.getP4kDeletedAt()).isNull());
    assertThat(changes(result.runId(), P4kChangeType.VANISHED))
        .singleElement()
        .satisfies(c -> {
          assertThat(c.getKind()).isEqualTo(P4kKind.ITEM);
          assertThat(c.getGuid()).isEqualTo(gone.getP4kUuid());
          assertThat(c.isStillLiveElsewhere()).isFalse();
        });
    assertThat(sweep(result, P4kKind.ITEM))
        .isEqualTo(new P4kImportResultDto.Sweep(P4kKind.ITEM, P4kSweepOutcome.SWEPT, 20, 1, 1, 0));
  }

  @Test
  void aVanishedRowUexStillLists_isStamped_andFlaggedStillLive() {
    List<UUID> listed = P4kSweepFixtures.guids(19);
    fixtures.knownItems(listed);
    GameItem gone = fixtures.knownItem(UUID.randomUUID());
    gone.setUexItemId(987_655);

    P4kImportResultDto result = runs.apply(P4kTestCatalogues.bytes(itemsListing(listed)), false);

    assertThat(gone.getP4kDeletedAt()).isNotNull();
    assertThat(changes(result.runId(), P4kChangeType.VANISHED))
        .singleElement()
        .satisfies(c -> assertThat(c.isStillLiveElsewhere()).isTrue());
  }

  @Test
  void aRepeatedGuid_voidsTheCensus_andTheReportSaysWhy() {
    List<UUID> listed = new ArrayList<>(P4kSweepFixtures.guids(19));
    fixtures.knownItems(listed);
    GameItem gone = fixtures.knownItem(UUID.randomUUID());
    listed.add(listed.getFirst());

    P4kImportResultDto result = runs.apply(P4kTestCatalogues.bytes(itemsListing(listed)), false);

    assertThat(gone.getP4kDeletedAt()).isNull();
    assertSkipped(result, P4kSweepOutcome.SKIPPED_DUPLICATE_GUID);
  }

  @Test
  void anEmptyKind_stampsNothing_andTheReportSaysWhy() {
    GameItem known = fixtures.knownItem(UUID.randomUUID());

    P4kImportResultDto result = runs.apply(P4kTestCatalogues.bytes(itemsListing(List.of())), false);

    assertThat(known.getP4kDeletedAt()).isNull();
    assertSkipped(result, P4kSweepOutcome.SKIPPED_EMPTY);
  }

  @Test
  void aPartialKind_isMatchedButNotSwept_whileACompleteKindIsSwept() {
    List<UUID> listedItems = P4kSweepFixtures.guids(19);
    List<GameItem> items = fixtures.knownItems(listedItems);
    GameItem goneItem = fixtures.knownItem(UUID.randomUUID());
    List<UUID> listedShips = P4kSweepFixtures.guids(19);
    fixtures.knownShips(listedShips);
    ShipType goneShip = fixtures.knownShip(UUID.randomUUID());
    ObjectNode root = itemsListing(listedItems);
    P4kSweepFixtures.listOnly(root, "ships", P4kTestCatalogues.PLAYER_SHIP, listedShips);
    ((ObjectNode) root.get("completeness").get("items")).put("status", "partial");

    P4kImportResultDto result = runs.apply(P4kTestCatalogues.bytes(root), false);

    assertThat(items)
        .allSatisfy(row -> assertThat(row.getP4kSeenRunId()).isEqualTo(result.runId()));
    assertThat(goneItem.getP4kDeletedAt()).isNull();
    assertThat(goneShip.getP4kDeletedAt()).isNotNull();
    assertSkipped(result, P4kSweepOutcome.SKIPPED_PARTIAL);
    assertThat(sweep(result, P4kKind.SHIP).outcome()).isEqualTo(P4kSweepOutcome.SWEPT);
  }

  @Test
  void aStampedRowMatchedAgain_isCleared_andReportedReappeared() {
    List<UUID> listed = P4kSweepFixtures.guids(20);
    List<GameItem> known = fixtures.knownItems(listed);
    GameItem back = known.getFirst();
    back.setP4kDeletedAt(Instant.parse("2026-01-01T00:00:00Z"));

    P4kImportResultDto result = runs.apply(P4kTestCatalogues.bytes(itemsListing(listed)), false);

    assertThat(back.getP4kDeletedAt()).isNull();
    assertThat(back.getP4kUuid()).isEqualTo(listed.getFirst());
    assertThat(changes(result.runId(), P4kChangeType.REAPPEARED))
        .singleElement()
        .satisfies(c -> assertThat(c.getGuid()).isEqualTo(listed.getFirst()));
    assertThat(sweep(result, P4kKind.ITEM).reappeared()).isEqualTo(1);
  }

  @Test
  void sixPercentWithoutTheBox_matchesTheKindButDoesNotSweepIt() {
    List<UUID> listed = P4kSweepFixtures.guids(47);
    List<GameItem> kept = fixtures.knownItems(listed);
    List<GameItem> gone = fixtures.knownItems(P4kSweepFixtures.guids(3));
    int before = overrideEvents().size();

    P4kImportResultDto result = runs.apply(P4kTestCatalogues.bytes(itemsListing(listed)), false);

    assertThat(kept).allSatisfy(row -> assertThat(row.getP4kSeenRunId()).isEqualTo(result.runId()));
    assertThat(gone).allSatisfy(row -> assertThat(row.getP4kDeletedAt()).isNull());
    assertThat(changes(result.runId(), P4kChangeType.VANISHED)).isEmpty();
    assertSkipped(result, P4kSweepOutcome.SKIPPED_MASS_GUARD);
    assertThat(sweep(result, P4kKind.ITEM).toStamp()).isEqualTo(3);
    assertThat(overrideEvents()).hasSize(before);
  }

  @Test
  void sixPercentWithTheBox_sweeps_andOneAuditEventNamesTheKindTheCountAndTheShare() {
    List<UUID> listed = P4kSweepFixtures.guids(47);
    fixtures.knownItems(listed);
    List<GameItem> gone = fixtures.knownItems(P4kSweepFixtures.guids(3));
    int before = overrideEvents().size();

    P4kImportResultDto result =
        runs.apply(P4kTestCatalogues.bytes(itemsListing(listed)), false, true);

    assertThat(gone).allSatisfy(row -> assertThat(row.getP4kDeletedAt()).isNotNull());
    List<AuditEventDto> events = overrideEvents();
    assertThat(events).hasSize(before + 1);
    assertThat(events)
        .filteredOn(e -> result.runId().equals(e.subjectId()))
        .singleElement()
        .satisfies(e -> assertThat(e.details()).isEqualTo("kind=ITEM count=3 sharePercent=6.0"));
  }

  @Test
  void fivePercentExactly_sweepsWithoutTheBox() {
    List<UUID> listed = P4kSweepFixtures.guids(19);
    fixtures.knownItems(listed);
    GameItem gone = fixtures.knownItem(UUID.randomUUID());
    int before = overrideEvents().size();

    runs.apply(P4kTestCatalogues.bytes(itemsListing(listed)), false);

    assertThat(gone.getP4kDeletedAt()).isNotNull();
    assertThat(overrideEvents()).hasSize(before);
  }

  @Test
  void aPreview_showsWhatTheSweepWouldStamp_andWritesNothing() {
    List<UUID> listed = P4kSweepFixtures.guids(47);
    fixtures.knownItems(listed);
    List<GameItem> gone = fixtures.knownItems(P4kSweepFixtures.guids(3));

    P4kImportService.P4kPreview preview =
        runs.previewWithReport(P4kTestCatalogues.bytes(itemsListing(listed)));

    assertThat(sweep(preview.result(), P4kKind.ITEM))
        .isEqualTo(
            new P4kImportResultDto.Sweep(
                P4kKind.ITEM, P4kSweepOutcome.SKIPPED_MASS_GUARD, 50, 3, 0, 0));
    assertThat(gone).allSatisfy(row -> assertThat(row.getP4kDeletedAt()).isNull());
    assertThat(preview.changes())
        .filteredOn(c -> c.getChangeType() == P4kChangeType.SWEEP_SKIPPED)
        .anySatisfy(c -> {
          assertThat(c.getKind()).isEqualTo(P4kKind.ITEM);
          assertThat(c.getFieldName()).isEqualTo("SKIPPED_MASS_GUARD");
          assertThat(c.getNewValue()).isEqualTo("3");
        });
  }

  /**
   * Asserts that the item sweep was skipped for a reason and the report says so.
   *
   * @param result the run
   * @param outcome the expected reason
   */
  private void assertSkipped(P4kImportResultDto result, P4kSweepOutcome outcome) {
    assertThat(sweep(result, P4kKind.ITEM).outcome()).isEqualTo(outcome);
    assertThat(changes(result.runId(), P4kChangeType.SWEEP_SKIPPED))
        .filteredOn(c -> c.getKind() == P4kKind.ITEM)
        .singleElement()
        .satisfies(c -> assertThat(c.getFieldName()).isEqualTo(outcome.name()));
    assertThat(changes(result.runId(), P4kChangeType.VANISHED))
        .filteredOn(c -> c.getKind() == P4kKind.ITEM)
        .isEmpty();
  }
}
