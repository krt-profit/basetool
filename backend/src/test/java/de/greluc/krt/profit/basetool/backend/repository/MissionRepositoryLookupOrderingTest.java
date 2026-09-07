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

package de.greluc.krt.profit.basetool.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionReferenceDto;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the result ordering of {@link MissionRepository#findAllActiveReference} — the query that
 * feeds the Einsatz (mission) filter and per-item association select of the warehouse (Lager)
 * views. The picker must show the newest missions first, so the clause is {@code ORDER BY
 * plannedStartTime DESC NULLS LAST, name ASC}.
 *
 * <p>Run against the real Postgres test container (Flyway-migrated schema), so the {@code NULLS
 * LAST} semantics are validated at production parity rather than against an H2 default that
 * diverges from Postgres. The Testcontainer is shared and other suites commit mission rows, so the
 * assertion filters the result down to the ids created here and checks their <em>relative</em>
 * order rather than asserting an exact, suite-global list.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MissionRepositoryLookupOrderingTest {

  /** Far-past cut-off; irrelevant for the {@code ACTIVE} fixtures (only terminal missions gate). */
  private static final Instant CUTOFF = Instant.parse("2020-01-01T00:00:00Z");

  @Autowired private MissionRepository missionRepository;
  @Autowired private SquadronRepository squadronRepository;

  /**
   * The three dated fixtures are deliberately named in the opposite order to their planned start
   * (newest = "Zulu", oldest = "Alpha"), so a result that came back sorted by name would visibly
   * differ from one sorted by planned start — proving the ordering is by time, not name. The
   * undated mission must trail all dated ones (NULLS LAST) regardless of its name.
   */
  @Test
  void findAllActiveReference_ordersNewestPlannedStartFirstThenNullsLast() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    Squadron squadron = new Squadron();
    squadron.setName("Lookup-Order-" + tag);
    squadron.setShorthand("LO" + tag);
    OrgUnit owner = squadronRepository.save(squadron);

    UUID newestId = saveMission(owner, "Zulu", Instant.parse("2026-03-01T00:00:00Z"));
    UUID middleId = saveMission(owner, "Mike", Instant.parse("2026-02-01T00:00:00Z"));
    UUID oldestId = saveMission(owner, "Alpha", Instant.parse("2026-01-01T00:00:00Z"));
    UUID undatedId = saveMission(owner, "Bravo", null);

    Set<UUID> mine = Set.of(newestId, middleId, oldestId, undatedId);
    List<MissionReferenceDto> result =
        missionRepository.findAllActiveReference(true, null, Set.of(), true, CUTOFF);

    List<UUID> mineInResultOrder =
        result.stream().map(MissionReferenceDto::id).filter(mine::contains).toList();

    assertThat(mineInResultOrder).containsExactly(newestId, middleId, oldestId, undatedId);
  }

  /**
   * Verifies the home-page "next mission" finder skips terminal-status missions. A {@code
   * COMPLETED} mission with an <em>earlier</em> future planned start must not be returned ahead of
   * a later {@code PLANNED} one — proving the {@code status IN (PLANNED, ACTIVE)} clause filters
   * before the {@code ORDER BY plannedStartTime ASC} tiebreaker. The fixtures use far-future (year
   * 2099) planned starts and a matching lower bound so they dominate the shared test container
   * regardless of what other suites have committed.
   */
  // covers REQ-MISSION-003 — next-mission banner only considers PLANNED/ACTIVE missions
  @Test
  void findFirstByPlannedStartTimeAfterAndStatusIn_skipsTerminalStatusEvenWhenItSortsEarlier() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    Squadron squadron = new Squadron();
    squadron.setName("Next-Status-" + tag);
    squadron.setShorthand("NS" + tag);
    OrgUnit owner = squadronRepository.save(squadron);

    Instant lowerBound = Instant.parse("2099-01-01T00:00:00Z");
    saveMission(owner, "Completed-Earlier", Instant.parse("2099-02-01T00:00:00Z"), "COMPLETED");
    UUID plannedId =
        saveMission(owner, "Planned-Later", Instant.parse("2099-03-01T00:00:00Z"), "PLANNED");

    Mission next =
        missionRepository
            .findFirstByPlannedStartTimeAfterAndStatusInOrderByPlannedStartTimeAsc(
                lowerBound, List.of("PLANNED", "ACTIVE"))
            .orElseThrow();

    assertThat(next.getId()).isEqualTo(plannedId);
  }

  /**
   * Verifies the org-unit-scoped next-mission lookup (REQ-MISSION-008) returns the caller's own
   * unit's soonest {@code PLANNED}/{@code ACTIVE} mission and ignores both terminal-status missions
   * and every foreign-unit mission — including a foreign <em>public</em> one with an earlier
   * planned start. This is the core of the home-page banner narrowing: a member must see their own
   * unit's next mission, never the organisation-wide one.
   */
  // covers REQ-MISSION-008 — banner scoped to the caller's own org unit(s)
  @Test
  void findNextScopedMission_returnsOwnUnitNextSkippingForeignAndTerminal() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    OrgUnit mine = newSquadron("Scoped-Mine-" + tag, "SM" + tag);
    OrgUnit foreign = newSquadron("Scoped-Foreign-" + tag, "SF" + tag);

    Instant lowerBound = Instant.parse("2098-01-01T00:00:00Z");
    // Foreign public mission with the globally-earliest start — must be excluded by scope.
    saveMission(
        foreign, "Foreign-Earliest", Instant.parse("2099-01-01T00:00:00Z"), "PLANNED", false);
    // Own terminal mission earlier than the eligible one — must be excluded by status.
    saveMission(mine, "Mine-Terminal", Instant.parse("2099-01-15T00:00:00Z"), "COMPLETED", false);
    UUID mineSoonId =
        saveMission(mine, "Mine-Soon", Instant.parse("2099-02-01T00:00:00Z"), "PLANNED", false);
    UUID mineLaterId =
        saveMission(mine, "Mine-Later", Instant.parse("2099-03-01T00:00:00Z"), "PLANNED", false);

    List<Mission> head =
        missionRepository.findNextScopedMission(
            lowerBound,
            List.of("PLANNED", "ACTIVE"),
            null,
            Set.of(mine.getId()),
            PageRequest.of(0, 1));

    assertThat(head).extracting(Mission::getId).containsExactly(mineSoonId);

    // A wider page proves the full eligible set is exactly the own unit's live missions in order.
    List<Mission> all =
        missionRepository.findNextScopedMission(
            lowerBound,
            List.of("PLANNED", "ACTIVE"),
            null,
            Set.of(mine.getId()),
            PageRequest.of(0, 50));
    assertThat(all).extracting(Mission::getId).containsExactly(mineSoonId, mineLaterId);
  }

  /**
   * An internal mission of the caller's own org unit is eligible for the banner, and beats a later
   * public one.
   *
   * <p>This case used to prove the other half too: an {@code allowInternal=false} argument hid it,
   * described as "the defensive flag the service passes for a (rare) non-member caller that
   * nonetheless carries an org-unit scope". No caller ever passed {@code false} — the anonymous
   * banner was its only audience and ADR-0159 removed it — so the parameter is gone and internal
   * missions are unconditionally in scope. What is left is the half that describes the query as it
   * now behaves.
   */
  // covers REQ-MISSION-008 — an own-unit internal mission is banner-eligible
  @Test
  void findNextScopedMission_includesOwnInternalMission() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    OrgUnit mine = newSquadron("Scoped-Int-" + tag, "SI" + tag);

    Instant lowerBound = Instant.parse("2098-01-01T00:00:00Z");
    // Earlier and internal — the head, because an own-unit internal mission is in scope.
    UUID internalId =
        saveMission(mine, "Mine-Internal", Instant.parse("2099-02-01T00:00:00Z"), "PLANNED", true);
    saveMission(mine, "Mine-Public", Instant.parse("2099-03-01T00:00:00Z"), "PLANNED", false);

    List<Mission> head =
        missionRepository.findNextScopedMission(
            lowerBound,
            List.of("PLANNED", "ACTIVE"),
            null,
            Set.of(mine.getId()),
            PageRequest.of(0, 1));
    assertThat(head).extracting(Mission::getId).containsExactly(internalId);
  }

  /**
   * Persists a fresh {@link Squadron} owner for a scope fixture.
   *
   * @param name the squadron display name.
   * @param shorthand the squadron shorthand (kept short to satisfy the column constraint).
   * @return the persisted owner.
   */
  private OrgUnit newSquadron(String name, String shorthand) {
    Squadron squadron = new Squadron();
    squadron.setName(name);
    squadron.setShorthand(shorthand);
    return squadronRepository.save(squadron);
  }

  /**
   * Persists one {@code ACTIVE} mission so it is always returned by the lookup regardless of the
   * three-month terminal cut-off.
   *
   * @param owner the owning org unit (a {@code NOT NULL} FK on the mission row).
   * @param name the mission display name used by the {@code name} tiebreaker.
   * @param plannedStartTime the planned start, or {@code null} to exercise the NULLS-LAST branch.
   * @return the generated mission id.
   */
  private UUID saveMission(OrgUnit owner, String name, Instant plannedStartTime) {
    return saveMission(owner, name, plannedStartTime, "ACTIVE");
  }

  /**
   * Persists a non-internal mission with an explicit status, used by the next-mission status-filter
   * test.
   *
   * @param owner the owning org unit (a {@code NOT NULL} FK on the mission row).
   * @param name the mission display name used by the {@code name} tiebreaker.
   * @param plannedStartTime the planned start, or {@code null} to exercise the NULLS-LAST branch.
   * @param status the mission status (e.g. {@code PLANNED}, {@code ACTIVE}, {@code COMPLETED}).
   * @return the generated mission id.
   */
  private UUID saveMission(OrgUnit owner, String name, Instant plannedStartTime, String status) {
    return saveMission(owner, name, plannedStartTime, status, false);
  }

  /**
   * Persists a mission with an explicit status and internal flag, used by the org-unit-scoped
   * next-mission tests.
   *
   * @param owner the owning org unit (a {@code NOT NULL} FK on the mission row).
   * @param name the mission display name used by the {@code name} tiebreaker.
   * @param plannedStartTime the planned start, or {@code null} to exercise the NULLS-LAST branch.
   * @param status the mission status (e.g. {@code PLANNED}, {@code ACTIVE}, {@code COMPLETED}).
   * @param isInternal whether the mission is internal (hidden from public/guest visibility).
   * @return the generated mission id.
   */
  private UUID saveMission(
      OrgUnit owner, String name, Instant plannedStartTime, String status, boolean isInternal) {
    Mission mission = new Mission();
    mission.setName(name);
    mission.setStatus(status);
    mission.setIsInternal(isInternal);
    mission.setOwningOrgUnit(owner);
    mission.setPlannedStartTime(plannedStartTime);
    return missionRepository.save(mission).getId();
  }
}
