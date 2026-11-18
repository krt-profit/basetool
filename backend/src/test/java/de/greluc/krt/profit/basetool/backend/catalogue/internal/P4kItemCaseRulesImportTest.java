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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Item cases open only where an admin can decide something, an item's identity follows the record
 * its own facts name, and an APPLY settles the cases it no longer raises (REQ-P4K-009, REQ-P4K-016,
 * REQ-P4K-054, REQ-P4K-055).
 */
@SpringBootTest
@Transactional
class P4kItemCaseRulesImportTest {

  /** The fixture's seat access: named, {@code playability} {@code null}. */
  private static final UUID SEAT = UUID.fromString("000ec670-7acc-4771-b567-7c939d2a80c0");

  /** The fixture's Custodian SMG: a player item. */
  private static final UUID CUSTODIAN = UUID.fromString("00335844-8889-4c40-b4c5-e4d8496838cf");

  /** A copy of the Custodian SMG the test adds: same name, type, sub-type and size. */
  private static final UUID CUSTODIAN_COPY =
      UUID.fromString("0f000000-0000-4000-8000-0000000c0b01");

  /** A namesake of the Custodian SMG the test adds: same name, another size. */
  private static final UUID CUSTODIAN_S3 = UUID.fromString("0f000000-0000-4000-8000-0000000c0b03");

  /** An item the test adds; a row holding its GUID moves to {@link #OTHER_POSTER}. */
  private static final UUID POSTER = UUID.fromString("0f000000-0000-4000-8000-0000000c0b10");

  /** An item the test adds that a row holding {@link #POSTER} names by its own facts. */
  private static final UUID OTHER_POSTER = UUID.fromString("0f000000-0000-4000-8000-0000000c0b11");

  /** The class name of {@link #OTHER_POSTER}. */
  private static final String OTHER_POSTER_CLASS = "carryable_poster_other";

  /** The fixture's heavy-armour blueprint. */
  private static final UUID HEAVY_BLUEPRINT =
      UUID.fromString("005d95db-96ca-45b7-9647-7e7537b8fac8");

  @Autowired private P4kImportService imports;
  @Autowired private P4kImportJobRepository jobs;
  @Autowired private P4kReviewCaseRepository cases;
  @Autowired private P4kImportChangeRepository changes;
  @Autowired private GameItemRepository items;

  private P4kImportRuns runs;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(imports, jobs);
  }

  @Test
  void anUnclearItemOpensACaseOnlyOnceABlueprintProducesIt() {
    UUID first = runs.apply(P4kTestCatalogues.fixtureBytes(), true).runId();

    assertThat(hasCase(SEAT, P4kReviewReason.UNCLEAR_PLAYABILITY)).isFalse();
    assertThat(P4kImportRuns.report(changes, first))
        .anyMatch(change -> is(change, P4kChangeType.UNMATCHED, SEAT));

    runs.apply(P4kTestCatalogues.bytes(producedByBlueprint(SEAT)), true);

    assertThat(hasCase(SEAT, P4kReviewReason.UNCLEAR_PLAYABILITY)).isTrue();
    assertThat(items.findByP4kUuid(SEAT)).isEmpty();
  }

  @Test
  void anObtainableItemWithAPlaceholderNameIsNeitherCreatedNorReviewed() {
    ObjectNode root = producedByBlueprint(SEAT);
    ObjectNode seat = P4kTestCatalogues.record(root, "items", SEAT);
    ((ObjectNode) seat.get("name")).put("en", "PH - hdh_boots_01_01_13");
    seat.put("playability", "player");

    runs.apply(P4kTestCatalogues.bytes(root), true);

    assertThat(hasCase(SEAT, P4kReviewReason.UNCLEAR_PLAYABILITY)).isFalse();
    assertThat(items.findByP4kUuid(SEAT)).isEmpty();
  }

  @Test
  void aRowHoldingAnotherListedItemIsNoNameCandidate_andCoversACopyOfItsItem() {
    GameItem custodian = items.saveAndFlush(item("Custodian SMG", CUSTODIAN));
    ObjectNode root = P4kTestCatalogues.tree();
    ObjectNode original = P4kTestCatalogues.record(root, "items", CUSTODIAN);
    ObjectNode copy = original.deepCopy();
    copy.put("guid", CUSTODIAN_COPY.toString());
    copy.put("className", "ksar_smg_energy_01_contestedzonereward_tow");
    ObjectNode namesake = original.deepCopy();
    namesake.put("guid", CUSTODIAN_S3.toString());
    namesake.put("className", "ksar_smg_energy_01_s3");
    namesake.put("size", 3);
    ((ArrayNode) root.get("items")).add(copy).add(namesake);

    UUID runId = runs.apply(P4kTestCatalogues.bytes(root), true).runId();

    assertThat(custodian.getP4kUuid()).isEqualTo(CUSTODIAN);
    assertThat(hasCase(CUSTODIAN_COPY, P4kReviewReason.GUID_CONFLICT)).isFalse();
    assertThat(hasCase(CUSTODIAN_S3, P4kReviewReason.GUID_CONFLICT)).isFalse();
    assertThat(items.findByP4kUuid(CUSTODIAN_COPY)).isEmpty();
    assertThat(items.findByP4kUuid(CUSTODIAN_S3))
        .hasValueSatisfying(row -> assertThat(row.getId()).isNotEqualTo(custodian.getId()));
    assertThat(P4kImportRuns.report(changes, runId))
        .anyMatch(change -> is(change, P4kChangeType.COVERED, CUSTODIAN_COPY));
  }

  @Test
  void aRowStampedWithACopyFollowsTheItemItsWikiUuidNames_andTheCopyIsCovered() {
    GameItem custodian = item("Custodian SMG", CUSTODIAN_COPY);
    custodian.setExternalUuid(CUSTODIAN);
    custodian.setClassName("ksar_smg_energy_01_contestedzonereward");
    items.saveAndFlush(custodian);
    ObjectNode root = P4kTestCatalogues.tree();
    ObjectNode copy = P4kTestCatalogues.record(root, "items", CUSTODIAN).deepCopy();
    copy.put("guid", CUSTODIAN_COPY.toString());
    copy.put("className", "ksar_smg_energy_01_contestedzonereward_tow");
    ((ArrayNode) root.get("items")).add(copy);

    UUID runId = runs.apply(P4kTestCatalogues.bytes(root), false).runId();

    assertThat(custodian.getP4kUuid()).isEqualTo(CUSTODIAN);
    assertThat(hasCase(CUSTODIAN, P4kReviewReason.GUID_CONFLICT)).isFalse();
    assertThat(hasCase(CUSTODIAN_COPY, P4kReviewReason.GUID_CONFLICT)).isFalse();
    assertThat(P4kImportRuns.report(changes, runId))
        .anyMatch(change -> is(change, P4kChangeType.COVERED, CUSTODIAN_COPY))
        .anyMatch(change ->
            is(change, P4kChangeType.CHANGED, CUSTODIAN)
                && "p4k_uuid".equals(change.getFieldName()));
  }

  @Test
  void aSweptRowHoldingADepartedGuidFollowsTheItemItsWikiUuidNames_andReappears() {
    GameItem custodian = item("Custodian SMG", UUID.randomUUID());
    custodian.setExternalUuid(CUSTODIAN);
    custodian.setP4kDeletedAt(Instant.now());
    items.saveAndFlush(custodian);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(custodian.getP4kUuid()).isEqualTo(CUSTODIAN);
    assertThat(custodian.getP4kDeletedAt()).isNull();
    assertThat(hasCase(CUSTODIAN, P4kReviewReason.GUID_CONFLICT)).isFalse();
  }

  @Test
  void aGuidAMovingRowGivesUpLandsOnTheRowItsWikiUuidNames_whicheverRowLoadsFirst() {
    GameItem wiki = items.saveAndFlush(wikiRow(POSTER));
    GameItem moving = item("Other Poster", POSTER);
    moving.setExternalUuid(OTHER_POSTER);
    items.saveAndFlush(moving);

    UUID runId = runs.apply(P4kTestCatalogues.bytes(withPosters()), false).runId();

    assertThat(items.findByP4kUuid(POSTER))
        .hasValueSatisfying(row -> assertThat(row.getId()).isEqualTo(wiki.getId()));
    assertThat(items.findByP4kUuid(OTHER_POSTER))
        .hasValueSatisfying(row -> assertThat(row.getId()).isEqualTo(moving.getId()));
    assertThat(hasCase(POSTER, P4kReviewReason.GUID_CONFLICT)).isFalse();
    assertThat(hasCase(OTHER_POSTER, P4kReviewReason.GUID_CONFLICT)).isFalse();
    assertThat(P4kImportRuns.report(changes, runId))
        .anyMatch(change ->
            is(change, P4kChangeType.CHANGED, OTHER_POSTER)
                && "p4k_uuid".equals(change.getFieldName()));
  }

  @Test
  void aPreviewHandsTheGuidAMovingRowGivesUpOnAsTheApplyDoes() {
    items.saveAndFlush(wikiRow(POSTER));
    GameItem moving = item("Other Poster", POSTER);
    moving.setExternalUuid(OTHER_POSTER);
    items.saveAndFlush(moving);

    List<P4kImportChange> report =
        runs.previewWithReport(P4kTestCatalogues.bytes(withPosters())).changes();

    assertThat(report)
        .anyMatch(change ->
            is(change, P4kChangeType.CHANGED, OTHER_POSTER)
                && "p4k_uuid".equals(change.getFieldName()))
        .noneMatch(change ->
            is(change, P4kChangeType.CHANGED, POSTER) && "name".equals(change.getFieldName()))
        .noneMatch(change ->
            is(change, P4kChangeType.REVIEW, POSTER)
                || is(change, P4kChangeType.REVIEW, OTHER_POSTER));
  }

  @Test
  void aRowWhoseMoveDoesNotHappenKeepsItsGuid_andNoSecondRowTakesIt() {
    GameItem wiki = items.saveAndFlush(wikiRow(POSTER));
    GameItem stuck = item("Other Poster", POSTER);
    stuck.setClassName(OTHER_POSTER_CLASS);
    items.saveAndFlush(stuck);
    GameItem taker = item("Other Poster Wiki", null);
    taker.setExternalUuid(OTHER_POSTER);
    items.saveAndFlush(taker);

    runs.apply(P4kTestCatalogues.bytes(withPosters()), false);

    assertThat(items.findByP4kUuid(OTHER_POSTER))
        .hasValueSatisfying(row -> assertThat(row.getId()).isEqualTo(taker.getId()));
    assertThat(items.findByP4kUuid(POSTER))
        .hasValueSatisfying(row -> assertThat(row.getId()).isEqualTo(stuck.getId()));
    assertThat(wiki.getP4kUuid()).isNull();
  }

  @Test
  void anApplyClosesTheItemCasesItNoLongerRaises_andLeavesAnAdminDecision() {
    UUID firstRun = runs.apply(P4kTestCatalogues.fixtureBytes(), false).runId();
    P4kReviewCase stale = cases.saveAndFlush(guidConflict(CUSTODIAN, firstRun));
    P4kReviewCase decided = guidConflict(SEAT, firstRun);
    decided.setStatus(P4kReviewStatus.REVIEWED);
    decided.setResolution(P4kReviewResolution.KEEP);
    decided.setResolvedBy(UUID.randomUUID());
    decided.setResolvedAt(Instant.now());
    cases.saveAndFlush(decided);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(stale.getStatus()).isEqualTo(P4kReviewStatus.REVIEWED);
    assertThat(stale.getResolution()).isEqualTo(P4kReviewResolution.RESOLVED);
    assertThat(decided.getResolution()).isEqualTo(P4kReviewResolution.KEEP);
  }

  private boolean hasCase(UUID guid, P4kReviewReason reason) {
    return cases
        .findByKindAndGuidAndReason(P4kKind.ITEM, guid, reason)
        .filter(review -> review.getStatus() == P4kReviewStatus.OPEN)
        .isPresent();
  }

  private static boolean is(P4kImportChange change, P4kChangeType type, UUID guid) {
    return change.getChangeType() == type
        && change.getKind() == P4kKind.ITEM
        && guid.equals(change.getGuid());
  }

  /**
   * Builds the fixture with the heavy-armour blueprint producing a given item instead of its own.
   *
   * @param produced the item the blueprint produces
   * @return the catalogue tree
   */
  private static ObjectNode producedByBlueprint(UUID produced) {
    ObjectNode root = P4kTestCatalogues.tree();
    P4kTestCatalogues.record(root, "blueprints", HEAVY_BLUEPRINT)
        .put("producedItemGuid", produced.toString());
    return root;
  }

  /**
   * Builds the fixture with two poster records of their own names and class names, {@link #POSTER}
   * listed before {@link #OTHER_POSTER}.
   *
   * @return the catalogue tree
   */
  private static ObjectNode withPosters() {
    ObjectNode root = P4kTestCatalogues.tree();
    ObjectNode template = P4kTestCatalogues.record(root, "items", CUSTODIAN);
    ((ArrayNode) root.get("items"))
        .add(poster(template, POSTER, "carryable_poster", "Poster"))
        .add(poster(template, OTHER_POSTER, OTHER_POSTER_CLASS, "Other Poster"));
    return root;
  }

  private static ObjectNode poster(ObjectNode template, UUID guid, String className, String name) {
    ObjectNode poster = template.deepCopy();
    poster.put("guid", guid.toString());
    poster.put("className", className);
    ((ObjectNode) poster.get("name")).put("en", name);
    return poster;
  }

  /**
   * Builds a SC Wiki row of a record that holds no P4K GUID yet and carries the record's name.
   *
   * @param wikiUuid the record's GUID as its SC Wiki UUID
   * @return the row, unsaved
   */
  private static GameItem wikiRow(UUID wikiUuid) {
    GameItem wiki = item("Poster", null);
    wiki.setExternalUuid(wikiUuid);
    wiki.setSourceSystems(GameItemSourceSystem.WIKI_ONLY);
    return wiki;
  }

  private static GameItem item(String name, UUID p4kUuid) {
    GameItem item = new GameItem();
    item.setName(name);
    item.setKind(GameItemKind.GENERIC);
    item.setP4kUuid(p4kUuid);
    item.setSourceSystems(GameItemSourceSystem.BOTH);
    return item;
  }

  private static P4kReviewCase guidConflict(UUID guid, UUID runId) {
    P4kReviewCase review = new P4kReviewCase();
    review.setKind(P4kKind.ITEM);
    review.setGuid(guid);
    review.setReason(P4kReviewReason.GUID_CONFLICT);
    review.setFirstRunId(runId);
    review.setLastRunId(runId);
    return review;
  }
}
