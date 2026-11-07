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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * "P4K import due" compares the UEX and SC Wiki game versions with the last APPLY of known version
 * (REQ-OBS-022, REQ-P4K-047).
 */
@SpringBootTest
@Transactional
class P4kImportDueTest {

  private static final Instant BASE = Instant.parse("2100-01-01T00:00:00Z");

  @Autowired private P4kImportDueService service;
  @Autowired private P4kImportRunRepository runRepository;
  @Autowired private ExternalSyncRunRepository syncRunRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private long changeNumber = 90_000_000L;

  @BeforeEach
  void startFromNoRunsAndNoSourceVersions() {
    for (String table :
        new String[] {"game_item", "ship_type", "manufacturer", "material", "blueprint"}) {
      jdbcTemplate.update("UPDATE " + table + " SET p4k_seen_run_id = NULL");
    }
    jdbcTemplate.update("DELETE FROM p4k_review_case");
    jdbcTemplate.update("DELETE FROM p4k_import_run");
    jdbcTemplate.update("DELETE FROM external_sync_run");
    jdbcTemplate.update("UPDATE game_item SET uex_game_version_seen = NULL");
  }

  @Test
  void withNoRun_theImportIsDue() {
    P4kImportDueDto due = service.evaluate();

    assertThat(due.due()).isTrue();
    assertThat(due.basisVersion()).isNull();
    assertThat(due.basisOrigin()).isNull();
  }

  @Test
  void aWikiVersionNewerThanTheLastRunsPatch_makesTheImportDue() {
    run("4.10.2", P4kPatchVersionOrigin.PUBLIC_PATCH, 0);
    wikiSweep("4.10.3-LIVE.12990000");

    P4kImportDueDto due = service.evaluate();

    assertThat(due.due()).isTrue();
    assertThat(due.wikiVersion()).isEqualTo("4.10.3");
    assertThat(due.basisVersion()).isEqualTo("4.10.2");
  }

  @Test
  void equalVersions_leaveTheImportNotDue() {
    run("4.10.2", P4kPatchVersionOrigin.PUBLIC_PATCH, 0);
    wikiSweep("4.10.2-LIVE.12881860");
    uexVersion("4.10.2");

    assertThat(service.evaluate().due()).isFalse();
  }

  @Test
  void anOlderSourceVersion_leavesTheImportNotDue() {
    run("4.10.2", P4kPatchVersionOrigin.PUBLIC_PATCH, 0);
    wikiSweep("4.9.0-LIVE.1");

    assertThat(service.evaluate().due()).isFalse();
  }

  @Test
  void aUexVersionAboveABranchLowerBound_readsAsDue() {
    run("4.10.0", P4kPatchVersionOrigin.BRANCH_LOWER_BOUND, 0);
    uexVersion("4.10.1");

    P4kImportDueDto due = service.evaluate();

    assertThat(due.due()).isTrue();
    assertThat(due.uexVersion()).isEqualTo("4.10.1");
    assertThat(due.basisOrigin()).isEqualTo(P4kPatchVersionOrigin.BRANCH_LOWER_BOUND);
  }

  @Test
  void aLaterRunWithAnUnknownVersion_doesNotReplaceTheBasis() {
    run("4.10.2", P4kPatchVersionOrigin.PUBLIC_PATCH, 0);
    run(null, null, 1);
    wikiSweep("4.10.2-LIVE.12881860");

    P4kImportDueDto due = service.evaluate();

    assertThat(due.due()).isFalse();
    assertThat(due.basisVersion()).isEqualTo("4.10.2");
  }

  @Test
  void anIncompleteWikiSweep_isNoSourceVersion() {
    run("4.10.2", P4kPatchVersionOrigin.PUBLIC_PATCH, 0);
    ExternalSyncRun failed = wikiSweep("4.11.0-LIVE.1");
    failed.setStatus(SyncRunStatus.FAILED);
    syncRunRepository.saveAndFlush(failed);

    assertThat(service.evaluate().due()).isFalse();
  }

  private void run(
      @Nullable String patchVersion, @Nullable P4kPatchVersionOrigin origin, int hoursLater) {
    P4kImportRun run = new P4kImportRun();
    run.setChannel("LIVE");
    run.setBuildBranch("sc-alpha-4.10.2");
    run.setBuildVersion("4.10.197.2878");
    run.setChangeNumber(changeNumber++);
    run.setBuildId(UUID.randomUUID().toString());
    run.setPatchVersion(patchVersion);
    run.setPatchVersionOrigin(origin);
    run.setCreatedBy(UUID.randomUUID());
    run.setAppliedAt(BASE.plus(hoursLater, ChronoUnit.HOURS));
    runRepository.saveAndFlush(run);
  }

  @NotNull
  private ExternalSyncRun wikiSweep(@NotNull String gameVersion) {
    ExternalSyncRun sweep = new ExternalSyncRun();
    sweep.setId(UUID.randomUUID());
    sweep.setSourceSystem(SyncSourceSystem.SCWIKI);
    sweep.setAggregate(SyncAggregate.CATALOGUE);
    sweep.setStatus(SyncRunStatus.SUCCESS);
    sweep.setStartedAt(BASE);
    sweep.setFinishedAt(BASE.plusSeconds(60));
    sweep.setGameVersion(gameVersion);
    sweep.setSweepOutcome(SyncSweepOutcome.NOT_REQUIRED);
    return syncRunRepository.saveAndFlush(sweep);
  }

  private void uexVersion(@NotNull String version) {
    jdbcTemplate.update(
        "INSERT INTO game_item (id, name, uex_game_version_seen, kind, source_systems)"
            + " VALUES (?, ?, ?, 'GENERIC', 'UEX_ONLY')",
        UUID.randomUUID(),
        "Due test item " + version,
        version);
  }
}
