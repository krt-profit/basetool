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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionCrew;
import de.greluc.krt.profit.basetool.backend.model.MissionFrequency;
import de.greluc.krt.profit.basetool.backend.model.MissionOwnership;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.MissionUnit;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.FrequencyTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionCrewRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionFrequencyRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionOwnershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.OperationRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests the lifecycle and ownership methods of {@link MissionService}: {@code deleteMission},
 * {@code removeParticipant}, {@code updateMissionOwner}, {@code removeMissionFrequency} and {@code
 * findAllActiveReference}.
 */
@ExtendWith(MockitoExtension.class)
class MissionServiceLifecycleTest {

  @Mock private MissionRepository missionRepository;
  @Mock private UserRepository userRepository;
  @Mock private ShipRepository shipRepository;
  @Mock private ShipTypeRepository shipTypeRepository;
  @Mock private JobTypeRepository jobTypeRepository;
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @Mock private MissionUnitRepository missionUnitRepository;
  @Mock private MissionCrewRepository missionCrewRepository;
  @Mock private SquadronRepository squadronRepository;
  @Mock private FrequencyTypeRepository frequencyTypeRepository;
  @Mock private MissionFrequencyRepository missionFrequencyRepository;
  @Mock private MissionOwnershipRepository missionOwnershipRepository;
  @Mock private OperationRepository operationRepository;
  @Mock private UserService userService;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private AuthHelperService authHelperService;

  @Mock private AuditService auditService;

  @InjectMocks private MissionParticipantService missionParticipantService;
  private MissionService service;

  @BeforeEach
  void wireExtractedParticipantService() {
    service =
        new MissionService(
            missionRepository,
            missionParticipantRepository,
            userRepository,
            frequencyTypeRepository,
            missionFrequencyRepository,
            missionOwnershipRepository,
            operationRepository,
            userService,
            ownerScopeService,
            authHelperService,
            auditService,
            null,
            missionParticipantService,
            null);
  }

  private static final UUID MISSION_ID = UUID.randomUUID();
  private static final UUID USER_ID = UUID.randomUUID();

  @Nested
  class DeleteMissionTests {

    @Test
    void throwsNotFound_whenMissionDoesNotExist() {
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.empty());

      assertThrows(NotFoundException.class, () -> service.deleteMission(MISSION_ID));
      verify(missionRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recordsAudit_onDelete() {
      Mission mission = newMission();

      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission));

      service.deleteMission(MISSION_ID);

      verify(missionRepository).delete(mission);
      verify(auditService)
          .record(
              eq(de.greluc.krt.profit.basetool.backend.model.AuditEventType.MISSION_DELETED),
              any(),
              any(),
              isNull(),
              isNull());
    }

    @Test
    void detachesRefineryOrders_beforeDelete() {
      Mission mission = newMission();
      RefineryOrder o1 = new RefineryOrder();
      o1.setMission(mission);
      mission.getRefineryOrders().add(o1);

      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission));

      service.deleteMission(MISSION_ID);

      assertNull(o1.getMission());
      assertTrue(mission.getRefineryOrders().isEmpty());
      verify(missionRepository).delete(mission);
    }

    @Test
    void detachesSubMissions_beforeDelete() {
      Mission mission = newMission();
      Mission sub = new Mission();
      sub.setId(UUID.randomUUID());
      sub.setParent(mission);
      mission.getSubMissions().add(sub);

      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission));

      service.deleteMission(MISSION_ID);

      assertNull(sub.getParent(), "sub-mission's parent pointer must be null after detach");
      assertTrue(mission.getSubMissions().isEmpty());
      verify(missionRepository).delete(mission);
    }

    @Test
    void detachesRefineryOrdersAndSubMissions_inOneCall() {
      Mission mission = newMission();
      RefineryOrder order = new RefineryOrder();
      order.setMission(mission);
      mission.getRefineryOrders().add(order);

      Mission sub = new Mission();
      sub.setParent(mission);
      mission.getSubMissions().add(sub);

      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission));

      service.deleteMission(MISSION_ID);

      assertNull(order.getMission());
      assertNull(sub.getParent());
      verify(missionRepository).delete(mission);
    }

    @Test
    void missionWithEmptyCollections_isStillDeleted() {
      Mission mission = newMission();
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission));

      service.deleteMission(MISSION_ID);

      verify(missionRepository).delete(mission);
    }
  }

  @Nested
  class RemoveParticipantTests {

    @Test
    void throwsNotFound_whenMissionDoesNotExist() {
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class, () -> service.removeParticipant(MISSION_ID, UUID.randomUUID()));
    }

    @Test
    void throwsNotFound_whenParticipantNotOnMission() {
      Mission mission = newMission();
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission));

      NotFoundException ex =
          assertThrows(
              NotFoundException.class,
              () -> service.removeParticipant(MISSION_ID, UUID.randomUUID()));
      assertTrue(ex.getMessage().toLowerCase().contains("participant"));
    }

    @Test
    void happyPath_removesParticipantAndAnyCrewReferences() {
      UUID participantId = UUID.randomUUID();
      Mission mission = newMission();
      MissionParticipant participant = newParticipant(participantId);
      mission.getParticipants().add(participant);

      MissionUnit unitA = new MissionUnit();
      unitA.setId(UUID.randomUUID());
      MissionCrew crewA = new MissionCrew();
      crewA.setParticipant(participant);
      unitA.getCrew().add(crewA);
      MissionCrew foreignCrew = new MissionCrew();
      MissionParticipant foreignParticipant = newParticipant(UUID.randomUUID());
      foreignCrew.setParticipant(foreignParticipant);
      unitA.getCrew().add(foreignCrew);

      MissionUnit unitB = new MissionUnit();
      unitB.setId(UUID.randomUUID());
      MissionCrew crewB = new MissionCrew();
      crewB.setParticipant(participant);
      unitB.getCrew().add(crewB);

      mission.getAssignedUnits().add(unitA);
      mission.getAssignedUnits().add(unitB);

      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission));

      Mission result = service.removeParticipant(MISSION_ID, participantId);

      assertSame(mission, result);
      assertTrue(
          mission.getParticipants().isEmpty(),
          "participant must be removed from the participants set");
      assertEquals(
          1,
          unitA.getCrew().size(),
          "crew in unit A: only the participant's crew is gone, foreign crew kept");
      assertSame(foreignCrew, unitA.getCrew().iterator().next());
      assertTrue(
          unitB.getCrew().isEmpty(), "crew in unit B must also have the participant's row removed");
    }

    @Test
    void crewWithNullParticipant_isNotTouched() {
      UUID participantId = UUID.randomUUID();
      Mission mission = newMission();
      MissionParticipant participant = newParticipant(participantId);
      mission.getParticipants().add(participant);

      MissionUnit unit = new MissionUnit();
      MissionCrew nullPartCrew = new MissionCrew();
      nullPartCrew.setParticipant(null);
      unit.getCrew().add(nullPartCrew);
      mission.getAssignedUnits().add(unit);

      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission));

      service.removeParticipant(MISSION_ID, participantId);

      assertEquals(
          1,
          unit.getCrew().size(),
          "crew with null participant must survive (would NPE otherwise)");
    }
  }

  @Nested
  class RemoveMissionFrequencyTests {

    @Test
    void throwsNotFound_whenMissionMissing() {
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class,
          () -> service.removeMissionFrequency(MISSION_ID, UUID.randomUUID()));
    }

    @Test
    void throwsNotFound_whenFrequencyNotOnMission() {
      Mission mission = newMission();
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission));

      assertThrows(
          NotFoundException.class,
          () -> service.removeMissionFrequency(MISSION_ID, UUID.randomUUID()));
    }

    @Test
    void happyPath_removesFrequency() {
      UUID frequencyId = UUID.randomUUID();
      Mission mission = newMission();
      MissionFrequency freq = new MissionFrequency();
      freq.setId(frequencyId);
      mission.getFrequencies().add(freq);

      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission));

      Mission result = service.removeMissionFrequency(MISSION_ID, frequencyId);

      assertSame(mission, result);
      assertTrue(mission.getFrequencies().isEmpty());
    }

    @Test
    void frequencyWithNullId_doesNotMatchByIdEqualityCheck() {
      Mission mission = newMission();
      MissionFrequency unsaved = new MissionFrequency();
      unsaved.setId(null);
      mission.getFrequencies().add(unsaved);

      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission));

      assertThrows(
          NotFoundException.class,
          () -> service.removeMissionFrequency(MISSION_ID, UUID.randomUUID()));
      assertEquals(
          1,
          mission.getFrequencies().size(),
          "the null-id frequency must NOT have been removed by mistake");
    }
  }

  @Nested
  class UpdateMissionOwnerTests {

    @Test
    void throwsNotFound_whenMissionMissing() {
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class, () -> service.updateMissionOwner(MISSION_ID, USER_ID, 0L));
      verify(userRepository, never()).findById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void throwsNotFound_whenUserMissing() {
      Mission mission = newMission();
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission));
      when(userRepository.findPlainById(USER_ID)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class, () -> service.updateMissionOwner(MISSION_ID, USER_ID, 0L));
      verify(missionOwnershipRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void happyPath_setsOwnerAndUpsertsOwnership_newOwnership() {
      Mission mission = newMission();
      User user = newUser(USER_ID);

      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission));
      when(userRepository.findPlainById(USER_ID)).thenReturn(Optional.of(user));
      when(missionOwnershipRepository.findByMissionId(MISSION_ID)).thenReturn(Optional.empty());
      when(missionOwnershipRepository.saveAndFlush(org.mockito.ArgumentMatchers.any()))
          .thenAnswer(inv -> inv.getArgument(0));

      service.updateMissionOwner(MISSION_ID, USER_ID, 0L);

      assertSame(user, mission.getOwner(), "mission.owner must point to the looked-up user");

      ArgumentCaptor<MissionOwnership> captor = ArgumentCaptor.forClass(MissionOwnership.class);
      verify(missionOwnershipRepository, org.mockito.Mockito.times(2))
          .saveAndFlush(captor.capture());
      MissionOwnership saved = captor.getValue();
      assertSame(user, saved.getOwner());
      assertSame(
          mission,
          saved.getMission(),
          "freshly-created MissionOwnership must point to the looked-up mission");
    }

    @Test
    void happyPath_existingOwnership_isMutatedInPlace() {
      Mission mission = newMission();
      User newOwner = newUser(USER_ID);
      User oldOwner = newUser(UUID.randomUUID());

      MissionOwnership existing = new MissionOwnership();
      existing.setId(UUID.randomUUID());
      existing.setMission(mission);
      existing.setOwner(oldOwner);
      existing.setVersion(5L);

      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission));
      when(userRepository.findPlainById(USER_ID)).thenReturn(Optional.of(newOwner));
      when(missionOwnershipRepository.findByMissionId(MISSION_ID))
          .thenReturn(Optional.of(existing));
      when(missionOwnershipRepository.saveAndFlush(existing)).thenReturn(existing);

      service.updateMissionOwner(MISSION_ID, USER_ID, 5L);

      assertSame(
          newOwner,
          existing.getOwner(),
          "existing row must be mutated in place with the new owner");
      assertEquals(
          5L,
          existing.getVersion(),
          "version must not be touched directly — Hibernate bumps it on flush");
      verify(missionOwnershipRepository).saveAndFlush(existing);
    }
  }

  @Test
  void findAllActiveReference_delegatesToRepository() {
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(true, null, java.util.Set.of()));
    when(missionRepository.findAllActiveReference(
            eq(true),
            isNull(),
            eq(java.util.Set.of()),
            org.mockito.ArgumentMatchers.anyBoolean(),
            any(java.time.Instant.class)))
        .thenReturn(java.util.List.of());

    assertNotNull(service.findAllActiveReference());

    ArgumentCaptor<java.time.Instant> cutoffCaptor =
        ArgumentCaptor.forClass(java.time.Instant.class);
    verify(missionRepository)
        .findAllActiveReference(
            eq(true),
            isNull(),
            eq(java.util.Set.of()),
            org.mockito.ArgumentMatchers.anyBoolean(),
            cutoffCaptor.capture());
    java.time.Instant expected =
        java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusMonths(3).toInstant();
    long skewMinutes =
        Math.abs(java.time.Duration.between(cutoffCaptor.getValue(), expected).toMinutes());
    assertTrue(skewMinutes <= 5, "lookup cut-off must be roughly three months in the past");
  }

  @Test
  void findAllActiveReference_passesCurrentSquadronIdToRepository() {
    java.util.UUID squadronId = java.util.UUID.randomUUID();
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(false, squadronId, java.util.Set.of()));
    when(missionRepository.findAllActiveReference(
            eq(false),
            eq(squadronId),
            eq(java.util.Set.of()),
            org.mockito.ArgumentMatchers.anyBoolean(),
            any(java.time.Instant.class)))
        .thenReturn(java.util.List.of());

    service.findAllActiveReference();

    verify(missionRepository)
        .findAllActiveReference(
            eq(false),
            eq(squadronId),
            eq(java.util.Set.of()),
            org.mockito.ArgumentMatchers.anyBoolean(),
            any(java.time.Instant.class));
  }

  private Mission newMission() {
    Mission m = new Mission();
    m.setId(MISSION_ID);
    m.setVersion(1L);
    m.setParticipants(new HashSet<>());
    m.setAssignedUnits(new HashSet<>());
    m.setRefineryOrders(new HashSet<>());
    m.setSubMissions(new HashSet<>());
    m.setFrequencies(new HashSet<>());
    m.setManagers(new HashSet<>());
    return m;
  }

  private MissionParticipant newParticipant(UUID id) {
    MissionParticipant p = new MissionParticipant();
    p.setId(id);
    return p;
  }

  private User newUser(UUID id) {
    User u = new User();
    u.setId(id);
    u.setUsername("user-" + id);
    return u;
  }

  @SuppressWarnings("unused")
  private static final Set<UUID> UNUSED = Set.of();
}
