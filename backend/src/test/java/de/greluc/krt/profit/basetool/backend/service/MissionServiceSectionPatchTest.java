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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionOwnership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.MissionOwnershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit-Tests fuer die Section-Patch-Methoden in {@link MissionService}.
 *
 * <p>Verifiziert, dass:
 *
 * <ul>
 *   <li>ein erfolgreicher Section-Patch nur die Felder der jeweiligen Sektion aktualisiert,
 *   <li>bei abweichender {@code expectedVersion} eine {@link
 *       ObjectOptimisticLockingFailureException} (HTTP 409) geworfen wird,
 *   <li>die Zeitplan-Validierung (meeting &le; plannedStart &le; plannedEnd) weiterhin greift.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MissionServiceSectionPatchTest {

  @Mock private MissionRepository missionRepository;

  @Mock private MissionParticipantRepository missionParticipantRepository;

  @Mock private MissionOwnershipRepository missionOwnershipRepository;

  @Mock private UserRepository userRepository;

  @Mock private OwnerScopeService ownerScopeService;

  @Mock private AuditService auditService;

  @InjectMocks private MissionParticipantService missionParticipantService;

  @InjectMocks private MissionStructureService missionStructureService;

  private MissionService missionService;

  private UUID missionId;
  private Mission existing;

  @BeforeEach
  void setUp() {
    missionService =
        new MissionService(
            missionRepository,
            missionParticipantRepository,
            userRepository,
            null,
            null,
            missionOwnershipRepository,
            null,
            null,
            ownerScopeService,
            null,
            auditService,
            null,
            missionParticipantService,
            missionStructureService);
    missionId = UUID.randomUUID();
    existing = new Mission();
    existing.setId(missionId);
    existing.setVersion(7L);
    existing.setCoreVersion(4L);
    existing.setScheduleVersion(5L);
    existing.setFlagsVersion(6L);
    existing.setName("Old name");
    existing.setDescription("Old desc");
    existing.setStatus("PLANNED");
    existing.setIsInternal(false);
    existing.setPlannedStartTime(Instant.parse("2030-01-01T10:00:00Z"));
    existing.setPlannedEndTime(Instant.parse("2030-01-01T12:00:00Z"));

    lenient()
        .when(missionRepository.bumpCoreVersionIfMatches(eq(missionId), anyLong()))
        .thenReturn(1);
    lenient()
        .when(missionRepository.bumpScheduleVersionIfMatches(eq(missionId), anyLong()))
        .thenReturn(1);
    lenient()
        .when(missionRepository.bumpFlagsVersionIfMatches(eq(missionId), anyLong()))
        .thenReturn(1);
    lenient()
        .when(missionRepository.bumpPartyLeadVersionIfMatches(eq(missionId), anyLong()))
        .thenReturn(1);
    lenient()
        .when(missionRepository.bumpOwningOrgUnitVersionIfMatches(eq(missionId), anyLong()))
        .thenReturn(1);
  }

  @Test
  void updateCoreSection_shouldUpdateOnlyCoreFields_whenCoreVersionMatches() {
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));
    when(missionRepository.save(any(Mission.class))).thenAnswer(inv -> inv.getArgument(0));

    Mission result =
        missionService.updateCoreSection(
            missionId,
            "New name",
            "New desc",
            "https://example.org/cal",
            "PLANNED",
            null,
            null,
            4L);

    assertEquals("New name", result.getName());
    assertEquals("New desc", result.getDescription());
    assertEquals("https://example.org/cal", result.getCalendarLink());
    assertEquals("PLANNED", result.getStatus());
    assertEquals(5L, result.getCoreVersion());
    assertEquals(5L, result.getScheduleVersion());
    assertEquals(6L, result.getFlagsVersion());
    assertEquals(Instant.parse("2030-01-01T10:00:00Z"), result.getPlannedStartTime());
    assertFalse(result.getIsInternal());
  }

  @Test
  void updateCoreSection_shouldThrow409_whenCoreVersionMismatch() {
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));

    when(missionRepository.bumpCoreVersionIfMatches(missionId, 3L)).thenReturn(0);
    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> missionService.updateCoreSection(missionId, "X", null, null, null, null, null, 3L));
  }

  @Test
  void updateCoreSection_shouldAlsoBumpScheduleAndStampActualStart_whenStatusTransitionsToActive() {
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));
    when(missionRepository.save(any(Mission.class))).thenAnswer(inv -> inv.getArgument(0));

    Instant before = Instant.now();

    Mission result =
        missionService.updateCoreSection(
            missionId, "Old name", "Old desc", null, "ACTIVE", null, null, 4L);

    Instant after = Instant.now();

    assertNotNull(result.getActualStartTime());
    assertTrue(!result.getActualStartTime().isBefore(before));
    assertTrue(!result.getActualStartTime().isAfter(after));
    assertEquals("ACTIVE", result.getStatus());
    assertEquals(5L, result.getCoreVersion());
    assertEquals(6L, result.getScheduleVersion());
    assertEquals(6L, result.getFlagsVersion());
  }

  @Test
  void updateScheduleSection_shouldUpdateOnlyScheduleFields_whenScheduleVersionMatches() {
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));
    when(missionRepository.save(any(Mission.class))).thenAnswer(inv -> inv.getArgument(0));

    Instant meeting = Instant.parse("2030-01-01T09:45:00Z");
    Instant plannedStart = Instant.parse("2030-01-01T10:00:00Z");
    Instant plannedEnd = plannedStart.plus(2, ChronoUnit.HOURS);

    Mission result =
        missionService.updateScheduleSection(
            missionId, meeting, plannedStart, plannedEnd, null, null, 5L);

    assertEquals(meeting, result.getMeetingTime());
    assertEquals(plannedStart, result.getPlannedStartTime());
    assertEquals(plannedEnd, result.getPlannedEndTime());
    assertEquals("Old name", result.getName());
    assertEquals(4L, result.getCoreVersion());
    assertEquals(6L, result.getScheduleVersion());
    assertEquals(6L, result.getFlagsVersion());
  }

  @Test
  void updateScheduleSection_whenActualEndTimeSet_clampsParticipantsViaSingleBulkUpdate() {
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));
    when(missionRepository.save(any(Mission.class))).thenAnswer(inv -> inv.getArgument(0));

    Instant actualStart = Instant.parse("2030-01-01T10:00:00Z");
    Instant actualEnd = Instant.parse("2030-01-01T12:00:00Z");

    missionService.updateScheduleSection(missionId, null, null, null, actualStart, actualEnd, 5L);

    verify(missionParticipantRepository).clampCheckedInEndTimes(missionId, actualEnd);
  }

  @Test
  void updateScheduleSection_shouldThrow409_whenScheduleVersionMismatch() {
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));

    when(missionRepository.bumpScheduleVersionIfMatches(missionId, 4L)).thenReturn(0);
    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> missionService.updateScheduleSection(missionId, null, null, null, null, null, 4L));
  }

  @Test
  void updateScheduleSection_shouldRejectInvalidTimeOrder() {
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));

    Instant plannedStart = Instant.parse("2030-01-01T12:00:00Z");
    Instant plannedEnd = Instant.parse("2030-01-01T10:00:00Z");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            missionService.updateScheduleSection(
                missionId, null, plannedStart, plannedEnd, null, null, 5L));
  }

  @Test
  void updateFlagsSection_shouldFlipInternalFlag_whenFlagsVersionMatches() {
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));
    when(missionRepository.save(any(Mission.class))).thenAnswer(inv -> inv.getArgument(0));

    Mission result = missionService.updateFlagsSection(missionId, true, 6L);

    assertTrue(result.getIsInternal());
    assertEquals("Old name", result.getName());
    assertEquals(4L, result.getCoreVersion());
    assertEquals(5L, result.getScheduleVersion());
    assertEquals(7L, result.getFlagsVersion());
  }

  @Test
  void updateFlagsSection_shouldThrow409_whenFlagsVersionMismatch() {
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));

    when(missionRepository.bumpFlagsVersionIfMatches(missionId, 999L)).thenReturn(0);
    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> missionService.updateFlagsSection(missionId, true, 999L));
  }

  @Test
  void sectionPatches_acrossDisjointSections_doNotInvalidateEachOther() {
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));
    when(missionRepository.save(any(Mission.class))).thenAnswer(inv -> inv.getArgument(0));

    Mission afterCore =
        missionService.updateCoreSection(
            missionId, "New name", null, null, "PLANNED", null, null, 4L);

    assertEquals(5L, afterCore.getCoreVersion());
    assertEquals(6L, afterCore.getFlagsVersion());

    Mission afterFlags = missionService.updateFlagsSection(missionId, true, 6L);

    assertEquals(5L, afterFlags.getCoreVersion());
    assertEquals(7L, afterFlags.getFlagsVersion());
    assertEquals("New name", afterFlags.getName());
    assertTrue(afterFlags.getIsInternal());
  }

  @Test
  void removeMissionUnit_shouldNotCallMissionRepositorySave() {
    de.greluc.krt.profit.basetool.backend.model.MissionUnit unit =
        new de.greluc.krt.profit.basetool.backend.model.MissionUnit();
    UUID unitId = UUID.randomUUID();
    unit.setId(unitId);
    existing.getAssignedUnits().add(unit);
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));

    Mission result = missionService.removeMissionUnit(missionId, unitId);

    assertSame(existing, result);
    verify(missionRepository, never()).save(any(Mission.class));
  }

  @Test
  void removeManager_shouldNotCallMissionRepositorySave() {
    UUID userId = UUID.randomUUID();
    User manager = new User();
    manager.setId(userId);
    existing.getManagers().add(manager);
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));

    Mission result = missionService.removeManager(missionId, userId);

    assertSame(existing, result);
    verify(missionRepository, never()).save(any(Mission.class));
  }

  @Test
  void updateMissionOwner_shouldSucceed_whenOwnershipVersionMatches() {
    UUID newOwnerId = UUID.randomUUID();
    User newOwner = new User();
    newOwner.setId(newOwnerId);

    MissionOwnership ownership = new MissionOwnership();
    ownership.setId(UUID.randomUUID());
    ownership.setMission(existing);
    ownership.setVersion(3L);

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));
    when(userRepository.findPlainById(newOwnerId)).thenReturn(Optional.of(newOwner));
    when(missionOwnershipRepository.findByMissionId(missionId)).thenReturn(Optional.of(ownership));
    when(missionOwnershipRepository.saveAndFlush(any(MissionOwnership.class)))
        .thenAnswer(inv -> flushOwnership(inv.getArgument(0)));

    Mission result = missionService.updateMissionOwner(missionId, newOwnerId, 3L);

    assertSame(existing, result);
    assertNotNull(result.getOwner());
    assertEquals(newOwnerId, result.getOwner().getId());
    assertEquals(4L, result.getOwnershipVersion());
    verify(missionRepository, never()).save(any(Mission.class));
  }

  /**
   * Stands in for Hibernate's flush of a {@code MissionOwnership}: an insert seeds the version at
   * {@code 0}, an update bumps it by one.
   *
   * @param row the row being flushed
   * @return the same row, versioned as the database would leave it
   */
  private static MissionOwnership flushOwnership(MissionOwnership row) {
    row.setVersion(row.getVersion() == null ? 0L : row.getVersion() + 1);
    return row;
  }

  @Test
  void updateMissionOwner_firstChange_materialisesTheRowWithTheOldOwnerAndEndsAtVersionOne() {
    User oldOwner = new User();
    oldOwner.setId(UUID.randomUUID());
    existing.setOwner(oldOwner);
    UUID newOwnerId = UUID.randomUUID();
    User newOwner = new User();
    newOwner.setId(newOwnerId);
    java.util.List<UUID> ownerAtEachFlush = new java.util.ArrayList<>();

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));
    when(userRepository.findPlainById(newOwnerId)).thenReturn(Optional.of(newOwner));
    when(missionOwnershipRepository.findByMissionId(missionId)).thenReturn(Optional.empty());
    when(missionOwnershipRepository.saveAndFlush(any(MissionOwnership.class)))
        .thenAnswer(
            inv -> {
              MissionOwnership row = inv.getArgument(0);
              ownerAtEachFlush.add(row.getOwner().getId());
              return flushOwnership(row);
            });

    Mission result = missionService.updateMissionOwner(missionId, newOwnerId, 0L);

    assertEquals(java.util.List.of(oldOwner.getId(), newOwnerId), ownerAtEachFlush);
    assertEquals(1L, result.getOwnershipVersion());
    assertEquals(newOwnerId, result.getOwner().getId());
  }

  @Test
  void updateMissionOwner_shouldThrow409_whenNoRowYetButTheEchoIsNotZero() {
    UUID newOwnerId = UUID.randomUUID();
    User newOwner = new User();
    newOwner.setId(newOwnerId);

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));
    when(userRepository.findPlainById(newOwnerId)).thenReturn(Optional.of(newOwner));
    when(missionOwnershipRepository.findByMissionId(missionId)).thenReturn(Optional.empty());

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> missionService.updateMissionOwner(missionId, newOwnerId, 1L));
    verify(missionOwnershipRepository, never()).saveAndFlush(any(MissionOwnership.class));
  }

  @Test
  void updateMissionOwner_shouldThrow409_whenOwnershipVersionMismatch() {
    UUID newOwnerId = UUID.randomUUID();
    User newOwner = new User();
    newOwner.setId(newOwnerId);

    MissionOwnership ownership = new MissionOwnership();
    ownership.setId(UUID.randomUUID());
    ownership.setMission(existing);
    ownership.setVersion(3L);

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));
    when(userRepository.findPlainById(newOwnerId)).thenReturn(Optional.of(newOwner));
    when(missionOwnershipRepository.findByMissionId(missionId)).thenReturn(Optional.of(ownership));

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> missionService.updateMissionOwner(missionId, newOwnerId, 2L));
    verify(missionOwnershipRepository, never()).saveAndFlush(any(MissionOwnership.class));
    assertNull(existing.getOwner());
  }

  @Test
  void updateOwningOrgUnit_shouldReassignAndBumpOnlyItsCounter_whenVersionMatches() {
    UUID targetId = UUID.randomUUID();
    OrgUnit target = mock(OrgUnit.class);
    when(target.getKind()).thenReturn(OrgUnitKind.SQUADRON);
    when(target.getId()).thenReturn(targetId);
    existing.setOwningOrgUnitVersion(2L);
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));
    when(ownerScopeService.resolveReassignTargetOrgUnit(targetId)).thenReturn(target);
    when(missionRepository.save(any(Mission.class))).thenAnswer(inv -> inv.getArgument(0));

    Mission result = missionService.updateOwningOrgUnit(missionId, targetId, 2L);

    assertSame(target, result.getOwningOrgUnit());
    assertEquals(3L, result.getOwningOrgUnitVersion(), "owningOrgUnitVersion must be bumped");
    assertEquals(7L, result.getVersion(), "global Mission.version must stay untouched");
    assertEquals(4L, result.getCoreVersion(), "other section counters must stay untouched");
    ArgumentCaptor<AuditEventType> typeCaptor = ArgumentCaptor.forClass(AuditEventType.class);
    verify(auditService)
        .record(typeCaptor.capture(), eq(missionId), any(), isNull(), any(CharSequence.class));
    assertEquals(AuditEventType.MISSION_OWNING_ORG_UNIT_CHANGED, typeCaptor.getValue());
  }

  @Test
  void updateOwningOrgUnit_shouldAllowOwnerlessTarget_whenResolverReturnsNull() {
    OrgUnit previous = mock(OrgUnit.class);
    when(previous.getKind()).thenReturn(OrgUnitKind.SQUADRON);
    when(previous.getId()).thenReturn(UUID.randomUUID());
    existing.setOwningOrgUnit(previous);
    existing.setOwningOrgUnitVersion(0L);
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));
    when(ownerScopeService.resolveReassignTargetOrgUnit(null)).thenReturn(null);
    when(missionRepository.save(any(Mission.class))).thenAnswer(inv -> inv.getArgument(0));

    Mission result = missionService.updateOwningOrgUnit(missionId, null, 0L);

    assertNull(result.getOwningOrgUnit(), "ownerless target clears owningOrgUnit");
    assertEquals(1L, result.getOwningOrgUnitVersion());
    verify(auditService)
        .record(
            eq(AuditEventType.MISSION_OWNING_ORG_UNIT_CHANGED),
            eq(missionId),
            any(),
            isNull(),
            any(CharSequence.class));
  }

  @Test
  void updateOwningOrgUnit_shouldThrow409_whenOwningOrgUnitVersionMismatch() {
    existing.setOwningOrgUnitVersion(3L);
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));

    when(missionRepository.bumpOwningOrgUnitVersionIfMatches(missionId, 2L)).thenReturn(0);
    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> missionService.updateOwningOrgUnit(missionId, UUID.randomUUID(), 2L));
    verify(missionRepository, never()).save(any(Mission.class));
  }

  @Test
  void updateOwningOrgUnit_shouldPropagateAccessDenied_andNotPersist_whenTargetDisallowed() {
    UUID targetId = UUID.randomUUID();
    existing.setOwningOrgUnitVersion(0L);
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));
    when(ownerScopeService.resolveReassignTargetOrgUnit(targetId))
        .thenThrow(new AccessDeniedException("not assignable"));

    assertThrows(
        AccessDeniedException.class,
        () -> missionService.updateOwningOrgUnit(missionId, targetId, 0L));
    verify(missionRepository, never()).save(any(Mission.class));
  }

  @Test
  void updateOwningOrgUnit_shouldThrowNotFound_whenMissionMissing() {
    when(missionRepository.findById(missionId)).thenReturn(Optional.empty());

    assertThrows(
        NotFoundException.class,
        () -> missionService.updateOwningOrgUnit(missionId, UUID.randomUUID(), 0L));
  }

  @Test
  void setPartyLead_shouldLinkRegisteredUser_whenUserIdProvided() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);
    existing.setPartyLeadGuestName("Old Guest Lead");
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));
    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(missionRepository.save(any(Mission.class))).thenAnswer(inv -> inv.getArgument(0));

    Mission result = missionService.setPartyLead(missionId, userId, null, 0L);

    assertSame(user, result.getPartyLeadUser());
    assertNull(result.getPartyLeadGuestName());
    assertEquals(1L, result.getPartyLeadVersion());
  }

  @Test
  void setPartyLead_shouldStoreGuestName_whenOnlyGuestNameProvided() {
    User previous = new User();
    previous.setId(UUID.randomUUID());
    existing.setPartyLeadUser(previous);
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));
    when(missionRepository.save(any(Mission.class))).thenAnswer(inv -> inv.getArgument(0));

    Mission result = missionService.setPartyLead(missionId, null, "  Ghost Pilot  ", 0L);

    assertNull(result.getPartyLeadUser());
    assertEquals("Ghost Pilot", result.getPartyLeadGuestName());
    assertEquals(1L, result.getPartyLeadVersion());
  }

  @Test
  void setPartyLead_shouldClearPartyLead_whenNeitherUserNorGuestNameProvided() {
    existing.setPartyLeadGuestName("Someone");
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));
    when(missionRepository.save(any(Mission.class))).thenAnswer(inv -> inv.getArgument(0));

    Mission result = missionService.setPartyLead(missionId, null, "   ", 0L);

    assertNull(result.getPartyLeadUser());
    assertNull(result.getPartyLeadGuestName());
    assertEquals(1L, result.getPartyLeadVersion());
  }

  @Test
  void setPartyLead_shouldBumpOnlyPartyLeadVersion_andNotCallParentVersion() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));
    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(missionRepository.save(any(Mission.class))).thenAnswer(inv -> inv.getArgument(0));

    Mission result = missionService.setPartyLead(missionId, userId, null, 0L);

    assertEquals(1L, result.getPartyLeadVersion());
    assertEquals(4L, result.getCoreVersion());
    assertEquals(5L, result.getScheduleVersion());
    assertEquals(6L, result.getFlagsVersion());
    assertEquals(7L, result.getVersion());
  }

  @Test
  void setPartyLead_shouldThrow409_whenPartyLeadVersionMismatch() {
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));

    when(missionRepository.bumpPartyLeadVersionIfMatches(missionId, 5L)).thenReturn(0);
    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> missionService.setPartyLead(missionId, null, "Whoever", 5L));
  }

  @Test
  void setPartyLead_shouldThrow404_whenReferencedUserUnknown() {
    UUID userId = UUID.randomUUID();
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));
    when(userRepository.findPlainById(userId)).thenReturn(Optional.empty());

    assertThrows(
        NotFoundException.class, () -> missionService.setPartyLead(missionId, userId, null, 0L));
  }
}
