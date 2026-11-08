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
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kBuild;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/** Every successful APPLY is a run, and every matched row names it (REQ-P4K-005, REQ-P4K-006). */
@SpringBootTest
@Transactional
class P4kImportRunTest {

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private P4kImportRunRepository runRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private EntityManager entityManager;
  @Autowired private JdbcTemplate jdbcTemplate;

  private P4kImportRuns runs;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
  }

  @Test
  void anApply_writesOneRunWithBuildCompletenessAndCounts_andAPreviewWritesNone() {
    long before = runRepository.count();

    P4kImportResultDto preview = runs.preview(P4kTestCatalogues.fixtureBytes());
    assertThat(runRepository.count()).isEqualTo(before);
    assertThat(preview.runId()).isNull();

    P4kImportResultDto applied = runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(runRepository.count()).isEqualTo(before + 1);
    P4kImportRun run = runRepository.findById(applied.runId()).orElseThrow();
    assertThat(run.getChannel()).isEqualTo("LIVE");
    assertThat(run.getBuildBranch()).isEqualTo("sc-alpha-4.10.2");
    assertThat(run.getBuildVersion()).isEqualTo("4.10.197.2878");
    assertThat(run.getChangeNumber()).isEqualTo(12_913_470L);
    assertThat(run.getBuildId()).isEqualTo("2cae0dfc-a941-4dcc-b82a-543b08b52d97");
    assertThat(run.getToolVersion()).isEqualTo("0.4.2");
    assertThat(run.getExportedAt()).isNotNull();
    assertThat(run.getCreatedBy()).isEqualTo(P4kImportRuns.ADMIN);
    assertThat(run.getKinds())
        .extracting(P4kImportRunKind::getKind)
        .containsExactlyInAnyOrder(P4kKind.values());
    assertThat(run.getKinds())
        .allSatisfy(k -> assertThat(k.getCompleteness()).isEqualTo(P4kCompleteness.COMPLETE));
    P4kImportRunKind ships =
        run.getKinds().stream().filter(k -> k.getKind() == P4kKind.SHIP).findFirst().orElseThrow();
    assertThat(ships.getMatched()).isEqualTo(applied.ships().matched());
    assertThat(ships.getUnmatched()).isEqualTo(applied.ships().unmatched());
  }

  @Test
  void aBranchLowerBound_isRecordedAsTheBranch() {
    P4kImportRun run =
        runRepository
            .findById(runs.apply(P4kTestCatalogues.fixtureBytes(), false).runId())
            .orElseThrow();

    assertThat(run.getPatchVersion()).isEqualTo("4.10.2");
    assertThat(run.getPatchVersionOrigin()).isEqualTo(P4kPatchVersionOrigin.BRANCH_LOWER_BOUND);
  }

  @Test
  void aPublicPatch_isRecordedAsThePublicPatch() {
    ObjectNode root = P4kTestCatalogues.tree();
    P4kTestCatalogues.build(root).put("publicPatch", "4.10.2");

    P4kImportRun run =
        runRepository
            .findById(runs.apply(P4kTestCatalogues.bytes(root), false).runId())
            .orElseThrow();

    assertThat(run.getPatchVersion()).isEqualTo("4.10.2");
    assertThat(run.getPatchVersionOrigin()).isEqualTo(P4kPatchVersionOrigin.PUBLIC_PATCH);
  }

  @Test
  void theVersionRule_takesOnlyAnExactReleaseBranchAsALowerBound() {
    assertThat(P4kPatchVersion.of(new P4kBuild("sc-alpha-4.10.2", "v", "1", "b", "4.10.3")))
        .isEqualTo(new P4kPatchVersion("4.10.3", P4kPatchVersionOrigin.PUBLIC_PATCH));
    assertThat(P4kPatchVersion.of(new P4kBuild("sc-alpha-4.10.2", "v", "1", "b", null)))
        .isEqualTo(new P4kPatchVersion("4.10.2", P4kPatchVersionOrigin.BRANCH_LOWER_BOUND));
    assertThat(P4kPatchVersion.of(new P4kBuild("sc-alpha-4.10", "v", "1", "b", null))).isNull();
    assertThat(P4kPatchVersion.of(new P4kBuild("sc-beta-4.10.2", "v", "1", "b", null))).isNull();
  }

  @Test
  void aPublicPatchThatIsNoVersion_readsAsUnset() {
    assertThat(P4kPatchVersion.of(new P4kBuild("sc-alpha-4.10.2", "v", "1", "b", "LIVE")))
        .isEqualTo(new P4kPatchVersion("4.10.2", P4kPatchVersionOrigin.BRANCH_LOWER_BOUND));
    assertThat(P4kPatchVersion.of(new P4kBuild("sc-alpha-4.10", "v", "1", "b", "4.10.2-hotfix")))
        .isNull();
    assertThat(
            P4kPatchVersion.of(
                new P4kBuild("sc-alpha-4.10", "v", "1", "b", "4.10.2" + "0".repeat(40))))
        .isNull();
  }

  @Test
  void everyMatchedRow_carriesTheRun_alsoWhenNoValueChanged() {
    GameItem shield = new GameItem();
    shield.setName("Run test shield");
    shield.setClassName(P4kTestCatalogues.SHIELD_CLASS);
    gameItemRepository.save(shield);

    UUID first = runs.apply(P4kTestCatalogues.fixtureBytes(), false).runId();
    assertThat(shield.getP4kSeenRunId()).isEqualTo(first);
    assertThat(shield.getP4kUuid()).isEqualTo(P4kTestCatalogues.SHIELD);

    UUID second = runs.apply(P4kTestCatalogues.fixtureBytes(), false).runId();
    assertThat(shield.getP4kSeenRunId()).isEqualTo(second);
  }

  @Test
  void aPartialKind_isMatchedAndRecordedPartial() {
    ObjectNode root = P4kTestCatalogues.tree();
    ((ObjectNode) root.get("completeness"))
        .putObject("items")
        .put("status", "partial")
        .put("filter", "type=Shield");
    GameItem shield = new GameItem();
    shield.setName("Partial test shield");
    shield.setClassName(P4kTestCatalogues.SHIELD_CLASS);
    gameItemRepository.save(shield);

    P4kImportResultDto result = runs.apply(P4kTestCatalogues.bytes(root), false);

    assertThat(result.items().completeness()).isEqualTo(P4kCompleteness.PARTIAL);
    assertThat(result.ships().completeness()).isEqualTo(P4kCompleteness.COMPLETE);
    assertThat(shield.getP4kSeenRunId()).isEqualTo(result.runId());
    P4kImportRun run = runRepository.findById(result.runId()).orElseThrow();
    assertThat(run.getKinds())
        .filteredOn(k -> k.getKind() == P4kKind.ITEM)
        .singleElement()
        .satisfies(k -> assertThat(k.getCompletenessFilter()).isEqualTo("type=Shield"));
  }

  @Test
  void pruningTheJob_leavesTheRunWithoutAJobReference() {
    UUID runId = runs.apply(P4kTestCatalogues.fixtureBytes(), false).runId();
    UUID jobId = runRepository.findById(runId).orElseThrow().getJobId();
    entityManager.flush();

    jdbcTemplate.update("DELETE FROM p4k_import_job WHERE id = ?", jobId);

    UUID left =
        jdbcTemplate.queryForObject(
            "SELECT job_id FROM p4k_import_run WHERE id = ?", UUID.class, runId);
    assertThat(jobId).isNotNull();
    assertThat(left).isNull();
  }
}
