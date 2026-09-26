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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionObjective;
import de.greluc.krt.profit.basetool.backend.model.MissionObjectiveKind;
import de.greluc.krt.profit.basetool.backend.model.MissionStep;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.repository.MissionObjectiveRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionStepRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests of the DB-enforced mission section counters.
 *
 * <ul>
 *   <li>The conditional bump increments on a matching version and is a no-op on a stale one.
 *   <li>A section edit bumps only its own counter, never the row {@code @Version} or a sibling.
 *   <li>The deferrable unique {@code (mission_id, order_index)} constraint tolerates a reorder but
 *       rejects a real duplicate once checked immediately.
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MissionSectionLockDbEnforcementTest {

  @Autowired private SquadronRepository squadronRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private MissionStepRepository missionStepRepository;
  @Autowired private MissionObjectiveRepository missionObjectiveRepository;
  @Autowired private MissionTimelineService missionTimelineService;
  @Autowired private MissionService missionService;
  @Autowired private MissionParticipantService missionParticipantService;

  @PersistenceContext private EntityManager entityManager;

  @MockitoBean private JwtDecoder jwtDecoder;

  @Test
  void conditionalBump_incrementsOnMatchingEcho_andIsNoOpOnStaleEcho() {
    UUID id = persistPlannedMission("Bump Guard").getId();

    assertEquals(1, missionRepository.bumpCoreVersionIfMatches(id, 0L));
    entityManager.clear();
    assertEquals(1L, missionRepository.findById(id).orElseThrow().getCoreVersion());

    assertEquals(0, missionRepository.bumpCoreVersionIfMatches(id, 0L));
    entityManager.clear();
    assertEquals(1L, missionRepository.findById(id).orElseThrow().getCoreVersion());
  }

  @Test
  void coreSectionEdit_advancesOnlyCoreCounter_notRowVersionNorSiblings() {
    Mission seed = persistPlannedMission("Decouple Core");
    UUID id = seed.getId();
    Long rowVersionBefore = seed.getVersion();
    entityManager.flush();
    entityManager.clear();

    missionService.updateCoreSection(id, "Renamed", "desc", null, "PLANNED", null, "ARC-L1", 0L);
    entityManager.flush();
    entityManager.clear();

    Mission after = missionRepository.findById(id).orElseThrow();
    assertEquals("Renamed", after.getName());
    assertEquals(1L, after.getCoreVersion(), "core counter advances");
    assertEquals(
        rowVersionBefore,
        after.getVersion(),
        "row @Version must NOT bump on a core edit — every scalar is @OptimisticLock(excluded) and"
            + " @DynamicUpdate narrows the UPDATE, so a concurrent schedule/flags edit never 409s");
    assertEquals(0L, after.getScheduleVersion(), "sibling schedule counter untouched");
    assertEquals(0L, after.getFlagsVersion(), "sibling flags counter untouched");
  }

  @Test
  void partyLeadEdit_advancesOnlyPartyLeadCounter_notRowVersion() {
    Mission seed = persistPlannedMission("Decouple PartyLead");
    UUID id = seed.getId();
    Long rowVersionBefore = seed.getVersion();
    entityManager.flush();
    entityManager.clear();

    missionParticipantService.setPartyLead(id, null, "Ghost Lead", 0L);
    entityManager.flush();
    entityManager.clear();

    Mission after = missionRepository.findById(id).orElseThrow();
    assertEquals("Ghost Lead", after.getPartyLeadGuestName());
    assertEquals(
        1L, after.getPartyLeadVersion(), "party-lead counter advances (#1112 DB-enforced)");
    assertEquals(
        rowVersionBefore,
        after.getVersion(),
        "party-lead's whole write set is excluded; the row @Version must stay put so the DB check —"
            + " not the row version — is what makes two concurrent reassignments collide");
  }

  @Test
  void reorderSteps_swap_flushesWithoutViolation_becauseTheUniqueConstraintIsDeferred() {
    UUID id = persistPlannedMission("Reorder Steps").getId();
    missionTimelineService.addStep(id, "A", null, 0L);
    Mission withSteps = missionTimelineService.addStep(id, "B", null, 1L);

    List<MissionStep> ordered =
        withSteps.getSteps().stream()
            .sorted(Comparator.comparingInt(MissionStep::getOrderIndex))
            .toList();
    UUID first = ordered.get(0).getId();
    UUID second = ordered.get(1).getId();

    missionTimelineService.reorderSteps(id, List.of(second, first), 2L);
    assertDoesNotThrow(() -> entityManager.flush());
    entityManager.clear();

    List<MissionStep> now =
        missionRepository.findById(id).orElseThrow().getSteps().stream()
            .sorted(Comparator.comparingInt(MissionStep::getOrderIndex))
            .toList();
    assertEquals(second, now.get(0).getId());
    assertEquals(first, now.get(1).getId());
  }

  @Test
  void duplicateStepOrderIndex_isRejectedOnceTheDeferredConstraintIsCheckedImmediately() {
    Mission mission = persistPlannedMission("Dup Step Order");
    persistStep(mission, "A", 0);
    persistStep(mission, "B", 0);

    RuntimeException thrown =
        assertThrows(RuntimeException.class, this::forceImmediateConstraintCheck);
    assertTrue(
        mentions(thrown, "uq_mission_step_mission_order"),
        () -> "expected the mission-step order unique violation, got: " + thrown);
  }

  @Test
  void duplicateObjectiveOrderIndex_isRejectedOnceTheDeferredConstraintIsCheckedImmediately() {
    Mission mission = persistPlannedMission("Dup Goal Order");
    persistObjective(mission, "Primary", 0);
    persistObjective(mission, "Secondary", 0);

    RuntimeException thrown =
        assertThrows(RuntimeException.class, this::forceImmediateConstraintCheck);
    assertTrue(
        mentions(thrown, "uq_mission_objective_mission_order"),
        () -> "expected the mission-objective order unique violation, got: " + thrown);
  }

  /** Persists a minimal PLANNED mission owned by the seeded IRIDIUM squadron. */
  private Mission persistPlannedMission(String name) {
    Squadron iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    Mission mission = new Mission();
    mission.setOwningOrgUnit(iridium);
    mission.setName(name + " " + UUID.randomUUID());
    mission.setStatus("PLANNED");
    return missionRepository.saveAndFlush(mission);
  }

  /** Directly persists an Ablauf step at a fixed order index (bypassing the section guard). */
  private void persistStep(Mission mission, String title, int orderIndex) {
    MissionStep step = new MissionStep();
    step.setMission(mission);
    step.setTitle(title);
    step.setOrderIndex(orderIndex);
    step.setDone(false);
    missionStepRepository.saveAndFlush(step);
  }

  /** Directly persists a goal at a fixed order index (bypassing the section guard). */
  private void persistObjective(Mission mission, String title, int orderIndex) {
    MissionObjective objective = new MissionObjective();
    objective.setMission(mission);
    objective.setTitle(title);
    objective.setKind(MissionObjectiveKind.PRIMARY);
    objective.setOrderIndex(orderIndex);
    missionObjectiveRepository.saveAndFlush(objective);
  }

  /**
   * Makes all deferred constraints immediate on the current connection, so a pending duplicate
   * surfaces now as a wrapped {@code SQLException}.
   */
  private void forceImmediateConstraintCheck() {
    entityManager
        .unwrap(Session.class)
        .doWork(
            connection -> {
              try (var statement = connection.createStatement()) {
                statement.execute("SET CONSTRAINTS ALL IMMEDIATE");
              }
            });
  }

  /** Returns {@code true} if {@code needle} appears anywhere in the throwable's cause chain. */
  private static boolean mentions(Throwable thrown, String needle) {
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      if (t.getMessage() != null && t.getMessage().contains(needle)) {
        return true;
      }
    }
    return false;
  }
}
