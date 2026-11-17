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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import static de.greluc.krt.profit.basetool.backend.support.StatementCounter.preparedBy;
import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies against PostgreSQL that the patch-change history reads a fixed number of statements
 * however many phases and wipe exceptions it holds, and that each phase still counts its own asked
 * and relocated members (REQ-PATCH-008, REQ-PATCH-024, REQ-DATA-003).
 *
 * <p>Its live times lie in 2000, before every other test's rows; every patch change is removed
 * afterwards, as in {@link PatchChangeTriggerIntegrationTest}.
 */
@SpringBootTest
class PatchChangeHistoryNoNPlusOneTest {

  private static final Instant FIRST_LIVE_AT = Instant.parse("2000-01-01T00:00:00Z");

  /** More records than Hibernate's default batch fetch size of 100 loads in one batch. */
  private static final int RECORDS = 120;

  /** Every tenth record asks for a home location. */
  private static final int PROMPT_EVERY = 10;

  @Autowired private PatchChangeHistory history;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbc;

  private UUID early;
  private UUID late;
  private UUID material;
  private String materialName;

  @BeforeEach
  void setUp() {
    early = persistUser("history-early-", FIRST_LIVE_AT.minus(Duration.ofDays(365)));
    late = persistUser("history-late-", liveAt(1).plus(Duration.ofHours(12)));
    material = UUID.randomUUID();
    materialName = "History ore " + material;
    jdbc.update(
        """
        INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,
                              is_job_order, is_visible, source_systems)
        VALUES (?, ?, 'NO_REFINE', 'SCU', false, false, true, 'UEX_ONLY')
        """,
        material,
        materialName);
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM patch_change");
    jdbc.update("DELETE FROM material WHERE id = ?", material);
    for (UUID user : List.of(early, late)) {
      jdbc.update("DELETE FROM app_user WHERE id = ?", user);
    }
  }

  @Test
  void theHistoryReadsAFixedNumberOfStatementsHoweverManyPhasesItHolds() throws Exception {
    record(1, true);
    record(2, false);
    long few = statements();

    for (int i = 3; i <= RECORDS; i++) {
      record(i, i % PROMPT_EVERY == 0);
    }
    long many = statements();

    assertThat(many)
        .as("statements for %d records, %d for two (suspected N+1)", RECORDS, few)
        .isPositive()
        .isEqualTo(few);
  }

  @Test
  void eachPhaseCountsItsOwnAskedAndRelocatedMembersAndNamesItsExceptions() {
    UUID first = record(1, true);
    UUID second = record(2, true);
    UUID withoutPrompt = record(3, false);
    relocate(first, early);
    relocate(first, late);
    relocate(second, early);
    relocate(second, late);

    List<PatchChangeDto> rows = history.history();

    PatchChangePhaseDto firstPhase = phaseOf(rows, first);
    PatchChangePhaseDto secondPhase = phaseOf(rows, second);
    assertThat(firstPhase.state()).isEqualTo(PatchChangePhaseState.SUPERSEDED);
    assertThat(firstPhase.relocated()).isEqualTo(1L);
    assertThat(secondPhase.state()).isEqualTo(PatchChangePhaseState.OPEN);
    assertThat(secondPhase.relocated()).isEqualTo(2L);
    assertThat(secondPhase.asked() - firstPhase.asked()).isEqualTo(1L);
    assertThat(rowOf(rows, withoutPrompt).phase()).isNull();
    assertThat(rows)
        .allSatisfy(row ->
            assertThat(row.wipeExceptions())
                .singleElement()
                .extracting(WipeExceptionDto::name)
                .isEqualTo(materialName));
  }

  /**
   * Reads the history and counts the statements it prepared.
   *
   * @return the statement count
   * @throws Exception whatever the read throws
   */
  private long statements() throws Exception {
    return preparedBy(history::history);
  }

  /**
   * Finds a patch change's history row.
   *
   * @param rows the history
   * @param id the patch change
   * @return its row
   */
  private static @NotNull PatchChangeDto rowOf(
      @NotNull List<PatchChangeDto> rows, @NotNull UUID id) {
    return rows.stream().filter(row -> id.equals(row.id())).findFirst().orElseThrow();
  }

  /**
   * Finds a patch change's phase in the history.
   *
   * @param rows the history
   * @param id the patch change
   * @return its phase
   */
  private static @NotNull PatchChangePhaseDto phaseOf(
      @NotNull List<PatchChangeDto> rows, @NotNull UUID id) {
    return Objects.requireNonNull(rowOf(rows, id).phase());
  }

  /**
   * Returns the live time of the n-th record, one day after the one before.
   *
   * @param n the record's position, from 1
   * @return its live time
   */
  private static @NotNull Instant liveAt(int n) {
    return FIRST_LIVE_AT.plus(Duration.ofDays(n - 1L));
  }

  /**
   * Inserts a patch change triggered by the early member, with one keeping exception for the test's
   * material.
   *
   * @param n its position, from 1; it decides the label and the live time
   * @param prompt whether it asks for a home location
   * @return its id
   */
  private @NotNull UUID record(int n, boolean prompt) {
    UUID id = UUID.randomUUID();
    Timestamp liveAt = Timestamp.from(liveAt(n));
    jdbc.update(
        """
        INSERT INTO patch_change (id, version, label, kind, live_at, triggered_at, triggered_by,
                                  cancel_refinery_orders, currency_wipe, home_location_prompt)
        VALUES (?, 0, ?, 'REBUILD', ?, ?, ?, false, false, ?)
        """,
        id,
        "0." + n,
        liveAt,
        liveAt,
        early,
        prompt);
    jdbc.update(
        "INSERT INTO patch_change_wipe_exception (patch_change_id, target, catalogue_id, mode)"
            + " VALUES (?, 'MATERIAL', ?, 'KEEP')",
        id,
        material);
    return id;
  }

  /**
   * Records a member's relocation in a phase.
   *
   * @param patchChange the phase's patch change
   * @param user the member
   */
  private void relocate(@NotNull UUID patchChange, @NotNull UUID user) {
    jdbc.update(
        """
        INSERT INTO patch_change_relocation (patch_change_id, user_id, chosen_by, relocated_at,
                                             rows_moved, ships_moved)
        VALUES (?, ?, 'SELF', now(), 0, 0)
        """,
        patchChange,
        user);
  }

  /**
   * Persists an active member with a creation time.
   *
   * @param prefix the username's prefix
   * @param createdAt the account's creation time
   * @return the member's id
   */
  private @NotNull UUID persistUser(@NotNull String prefix, @NotNull Instant createdAt) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(prefix + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    UUID id = userRepository.saveAndFlush(user).getId();
    jdbc.update("UPDATE app_user SET created_at = ? WHERE id = ?", Timestamp.from(createdAt), id);
    return id;
  }
}
