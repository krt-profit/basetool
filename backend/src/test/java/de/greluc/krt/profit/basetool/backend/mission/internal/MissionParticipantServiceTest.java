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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.identity.api.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mission.api.MemberPayoutSettings;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionCrew;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit tests for {@link MissionParticipantService}: sign-up, removal, party lead and co-managers,
 * each against mocks of the service's own collaborators.
 */
@ExtendWith(MockitoExtension.class)
class MissionParticipantServiceTest {

  private static final UUID MISSION_ID = UUID.randomUUID();

  @Mock private MissionRepository missionRepository;

  @Mock private MissionEditor missionEditor;
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @Mock private UserRepository userRepository;
  @Mock private MemberPayoutSettings memberPayoutSettings;
  @Mock private OrgUnitMembershipQueryService orgUnitMembershipQueryService;
  @Mock private AuditRecorder auditRecorder;

  /**
   * The name resolution is its own unit ({@code ParticipantTargetResolverTest}); here it passes
   * every submission through unresolved by default, and the one case about name matching stubs the
   * match it needs.
   */
  @Mock private ParticipantTargetResolver participantTargetResolver;

  @Mock private MissionNotificationPublisher notificationPublisher;
  @InjectMocks private MissionParticipantService missionParticipantService;

  @BeforeEach
  void wireMissionEditor() {
    MissionEditorFixture.delegate(missionEditor, missionRepository, auditRecorder);
  }

  /** Stubs the resolver's pass-through default leniently. */
  @BeforeEach
  void passNamesThroughUnresolved() {
    lenient()
        .when(participantTargetResolver.resolve(any(), any(), any()))
        .thenAnswer(
            inv ->
                new ParticipantTargetResolver.ParticipantTarget(
                    inv.getArgument(0), inv.getArgument(1)));
  }

  @Nested
  class AddParticipantTests {

    @Test
    void addParticipant_ShouldMatchGuestNameToExistingUserCaseInsensitive() {
      Mission mission = new Mission();
      mission.setId(MISSION_ID);

      UUID userId = UUID.randomUUID();
      User existingUser = new User();
      existingUser.setId(userId);
      existingUser.setUsername("testuser");

      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission));
      when(participantTargetResolver.resolve(null, "TestUser", "Participant name is ambiguous."))
          .thenReturn(new ParticipantTargetResolver.ParticipantTarget(userId, null));
      when(userRepository.findPlainById(userId)).thenReturn(Optional.of(existingUser));
      when(orgUnitMembershipQueryService.findAllMembershipsForUser(userId)).thenReturn(List.of());

      Mission result =
          missionParticipantService.addParticipant(
              MISSION_ID, null, "TestUser", null, "No comment");

      assertEquals(1, result.getParticipants().size());
      MissionParticipant participant = result.getParticipants().iterator().next();
      assertNotNull(participant.getUser(), "User should be mapped from guest name");
      assertEquals(userId, participant.getUser().getId());
      assertNull(participant.getGuestName(), "Guest name should be nullified since user was found");
      assertTrue(
          participant.getOrgUnits().isEmpty(),
          "a user with no membership must get no org-unit affiliation (no IRIDIUM fallback)");
    }

    @Test
    void addParticipant_ShouldPreFillPayoutPreferenceFromUserDefault() {
      Mission mission = new Mission();
      mission.setId(MISSION_ID);
      UUID userId = UUID.randomUUID();
      User user = new User();
      user.setId(userId);
      user.setUsername("donator");

      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission));
      when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
      when(memberPayoutSettings.defaultFor(userId))
          .thenReturn(Optional.of(PayoutPreference.DONATE));
      when(orgUnitMembershipQueryService.findAllMembershipsForUser(userId)).thenReturn(List.of());

      Mission result =
          missionParticipantService.addParticipant(MISSION_ID, userId, null, null, "No comment");

      MissionParticipant participant = result.getParticipants().iterator().next();
      assertEquals(
          PayoutPreference.DONATE,
          participant.getPayoutPreference(),
          "participant payout preference must be seeded from the signing-up user's profile"
              + " default");
    }

    @Test
    void addParticipant_ShouldKeepPayoutDefault_WhenUserHasNoDefault() {
      Mission mission = new Mission();
      mission.setId(MISSION_ID);
      UUID userId = UUID.randomUUID();
      User user = new User();
      user.setId(userId);
      user.setUsername("nopref");

      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission));
      when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
      when(orgUnitMembershipQueryService.findAllMembershipsForUser(userId)).thenReturn(List.of());

      Mission result =
          missionParticipantService.addParticipant(MISSION_ID, userId, null, null, "No comment");

      MissionParticipant participant = result.getParticipants().iterator().next();
      assertEquals(
          PayoutPreference.PAYOUT,
          participant.getPayoutPreference(),
          "a user with no profile default keeps the PAYOUT entity default");
    }

    @Test
    void addParticipant_ExplicitPayoutChoice_WinsOverProfileDefault() {
      Mission mission = new Mission();
      mission.setId(MISSION_ID);
      UUID userId = UUID.randomUUID();
      User user = new User();
      user.setId(userId);
      user.setUsername("explicit-chooser");

      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission));
      when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
      when(orgUnitMembershipQueryService.findAllMembershipsForUser(userId)).thenReturn(List.of());

      Mission result =
          missionParticipantService.addParticipant(
              MISSION_ID, userId, null, null, "No comment", null, PayoutPreference.PAYOUT);

      MissionParticipant participant = result.getParticipants().iterator().next();
      assertEquals(
          PayoutPreference.PAYOUT,
          participant.getPayoutPreference(),
          "an explicit sign-up payout choice must override the profile default");
    }
  }

  @Nested
  class RemoveParticipantTests {

    @Test
    void throwsNotFound_whenMissionDoesNotExist() {
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class,
          () -> missionParticipantService.removeParticipant(MISSION_ID, UUID.randomUUID()));
    }

    @Test
    void throwsNotFound_whenParticipantNotOnMission() {
      Mission mission = newMission();
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission));

      NotFoundException ex =
          assertThrows(
              NotFoundException.class,
              () -> missionParticipantService.removeParticipant(MISSION_ID, UUID.randomUUID()));
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

      Mission result = missionParticipantService.removeParticipant(MISSION_ID, participantId);

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

      missionParticipantService.removeParticipant(MISSION_ID, participantId);

      assertEquals(
          1,
          unit.getCrew().size(),
          "crew with null participant must survive (would NPE otherwise)");
    }
  }

  @Nested
  class PartyLeadAndManagerTests {

    private Mission existing;

    /** Seeds a mission whose section counters differ, so a stray bump shows in an assertion. */
    @BeforeEach
    void seedMission() {
      existing = newMission();
      existing.setVersion(7L);
      existing.setCoreVersion(4L);
      existing.setScheduleVersion(5L);
      existing.setFlagsVersion(6L);
      lenient()
          .when(missionRepository.bumpPartyLeadVersionIfMatches(eq(MISSION_ID), anyLong()))
          .thenReturn(1);
    }

    @Test
    void removeManager_shouldNotCallMissionRepositorySave() {
      UUID userId = UUID.randomUUID();
      User manager = new User();
      manager.setId(userId);
      existing.getManagers().add(manager);
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(existing));

      Mission result = missionParticipantService.removeManager(MISSION_ID, userId);

      assertSame(existing, result);
      verify(missionRepository, never()).save(any(Mission.class));
    }

    @Test
    void setPartyLead_shouldLinkRegisteredUser_whenUserIdProvided() {
      UUID userId = UUID.randomUUID();
      User user = new User();
      user.setId(userId);
      existing.setPartyLeadGuestName("Old Guest Lead");
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(existing));
      when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
      when(missionRepository.save(any(Mission.class))).thenAnswer(inv -> inv.getArgument(0));

      Mission result = missionParticipantService.setPartyLead(MISSION_ID, userId, null, 0L);

      assertSame(user, result.getPartyLeadUser());
      assertNull(result.getPartyLeadGuestName());
      assertEquals(1L, result.getPartyLeadVersion());
    }

    @Test
    void setPartyLead_shouldStoreGuestName_whenOnlyGuestNameProvided() {
      User previous = new User();
      previous.setId(UUID.randomUUID());
      existing.setPartyLeadUser(previous);
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(existing));
      when(missionRepository.save(any(Mission.class))).thenAnswer(inv -> inv.getArgument(0));

      Mission result =
          missionParticipantService.setPartyLead(MISSION_ID, null, "  Ghost Pilot  ", 0L);

      assertNull(result.getPartyLeadUser());
      assertEquals("Ghost Pilot", result.getPartyLeadGuestName());
      assertEquals(1L, result.getPartyLeadVersion());
    }

    @Test
    void setPartyLead_shouldClearPartyLead_whenNeitherUserNorGuestNameProvided() {
      existing.setPartyLeadGuestName("Someone");
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(existing));
      when(missionRepository.save(any(Mission.class))).thenAnswer(inv -> inv.getArgument(0));

      Mission result = missionParticipantService.setPartyLead(MISSION_ID, null, "   ", 0L);

      assertNull(result.getPartyLeadUser());
      assertNull(result.getPartyLeadGuestName());
      assertEquals(1L, result.getPartyLeadVersion());
    }

    @Test
    void setPartyLead_shouldBumpOnlyPartyLeadVersion_andNotCallParentVersion() {
      UUID userId = UUID.randomUUID();
      User user = new User();
      user.setId(userId);
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(existing));
      when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
      when(missionRepository.save(any(Mission.class))).thenAnswer(inv -> inv.getArgument(0));

      Mission result = missionParticipantService.setPartyLead(MISSION_ID, userId, null, 0L);

      assertEquals(1L, result.getPartyLeadVersion());
      assertEquals(4L, result.getCoreVersion());
      assertEquals(5L, result.getScheduleVersion());
      assertEquals(6L, result.getFlagsVersion());
      assertEquals(7L, result.getVersion());
    }

    @Test
    void setPartyLead_shouldThrow409_whenPartyLeadVersionMismatch() {
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(existing));

      when(missionRepository.bumpPartyLeadVersionIfMatches(MISSION_ID, 5L)).thenReturn(0);
      assertThrows(
          ObjectOptimisticLockingFailureException.class,
          () -> missionParticipantService.setPartyLead(MISSION_ID, null, "Whoever", 5L));
    }

    @Test
    void setPartyLead_shouldThrow404_whenReferencedUserUnknown() {
      UUID userId = UUID.randomUUID();
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(existing));
      when(userRepository.findPlainById(userId)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class,
          () -> missionParticipantService.setPartyLead(MISSION_ID, userId, null, 0L));
    }
  }

  private static Mission newMission() {
    Mission m = new Mission();
    m.setId(MISSION_ID);
    m.setVersion(1L);
    m.setParticipants(new HashSet<>());
    m.setAssignedUnits(new HashSet<>());
    m.setSubMissions(new HashSet<>());
    m.setFrequencies(new HashSet<>());
    m.setManagers(new HashSet<>());
    return m;
  }

  private static MissionParticipant newParticipant(UUID id) {
    MissionParticipant p = new MissionParticipant();
    p.setId(id);
    return p;
  }
}
