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

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies {@link MissionRepository#findUpcomingOwnOrSignedUp} against PostgreSQL: the union of the
 * caller's own-unit missions and the missions the caller is signed up for, de-duplicated, soonest
 * first, bounded by the page size (REQ-MISSION-031). Each test rolls back and filters to its own
 * ids.
 */
@SpringBootTest
@Transactional
class MissionUpcomingOwnOrSignedUpQueryDataTest {

  private static final Instant LOWER_BOUND = Instant.parse("2098-01-01T00:00:00Z");
  private static final List<String> STATUSES = List.of("PLANNED", "ACTIVE");

  @Autowired private MissionRepository missionRepository;
  @Autowired private MissionParticipantRepository participantRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private UserRepository userRepository;

  /**
   * An own-unit mission and a signed-up mission of another unit are both returned, merged and sorted
   * by planned start; a foreign mission the caller is not signed up for stays out.
   */
  @Test
  void unionsOwnUnitAndSignedUpMissionsSoonestFirst() {
    User member = newUser();
    OrgUnit mine = newSquadron();
    OrgUnit foreign = newSquadron();
    UUID signedUpForeign = saveMission(foreign, "2099-01-01T00:00:00Z", "PLANNED", false);
    UUID mineLater = saveMission(mine, "2099-03-01T00:00:00Z", "PLANNED", true);
    UUID mineSoon = saveMission(mine, "2099-02-01T00:00:00Z", "ACTIVE", false);
    UUID foreignOther = saveMission(foreign, "2099-01-15T00:00:00Z", "PLANNED", false);
    signUp(signedUpForeign, member);

    List<Mission> rows = upcoming(member, false, null, Set.of(mine.getId()), 10);

    assertThat(rows)
        .extracting(Mission::getId)
        .containsExactly(signedUpForeign, mineSoon, mineLater)
        .doesNotContain(foreignOther);
  }

  /** A mission that is both in the caller's unit and signed up for appears once. */
  @Test
  void aMissionInBothSetsAppearsOnce() {
    User member = newUser();
    OrgUnit mine = newSquadron();
    UUID both = saveMission(mine, "2099-02-01T00:00:00Z", "PLANNED", false);
    signUp(both, member);

    List<Mission> rows = upcoming(member, false, null, Set.of(mine.getId()), 10);

    assertThat(rows).extracting(Mission::getId).containsExactly(both);
  }

  /** Terminal missions never qualify, whether own-unit or signed up for. */
  @Test
  void skipsTerminalStatusAndPastMissions() {
    User member = newUser();
    OrgUnit mine = newSquadron();
    UUID signedUpDone = saveMission(mine, "2099-02-01T00:00:00Z", "COMPLETED", false);
    saveMission(mine, "2099-02-02T00:00:00Z", "CANCELLED", false);
    saveMission(mine, "2097-01-01T00:00:00Z", "PLANNED", false);
    signUp(signedUpDone, member);
    UUID planned = saveMission(mine, "2099-03-01T00:00:00Z", "PLANNED", false);

    List<Mission> rows = upcoming(member, false, null, Set.of(mine.getId()), 10);

    assertThat(rows).extracting(Mission::getId).containsExactly(planned);
  }

  /** A signed-up internal mission of a unit the caller cannot see stays out. */
  @Test
  void aSignedUpInternalMissionOfAnInvisibleUnitStaysOut() {
    User member = newUser();
    OrgUnit mine = newSquadron();
    OrgUnit foreign = newSquadron();
    UUID hidden = saveMission(foreign, "2099-02-01T00:00:00Z", "PLANNED", true);
    signUp(hidden, member);

    List<Mission> rows = upcoming(member, false, null, Set.of(mine.getId()), 10);

    assertThat(rows).isEmpty();
  }

  /** The pin narrows the own-unit part to the pinned unit; the sign-ups stay. */
  @Test
  void aPinNarrowsTheOwnUnitsButKeepsTheSignUps() {
    User member = newUser();
    OrgUnit pinned = newSquadron();
    OrgUnit other = newSquadron();
    UUID pinnedMission = saveMission(pinned, "2099-02-01T00:00:00Z", "PLANNED", false);
    saveMission(other, "2099-02-02T00:00:00Z", "PLANNED", false);
    UUID signedUpOther = saveMission(other, "2099-02-03T00:00:00Z", "PLANNED", false);
    signUp(signedUpOther, member);

    List<Mission> rows =
        upcoming(member, false, pinned.getId(), Set.of(pinned.getId(), other.getId()), 10);

    assertThat(rows).extracting(Mission::getId).containsExactly(pinnedMission, signedUpOther);
  }

  /** The page size bounds the result and keeps the soonest entries. */
  @Test
  void theLimitKeepsTheSoonestEntries() {
    User member = newUser();
    OrgUnit mine = newSquadron();
    UUID first = saveMission(mine, "2099-02-01T00:00:00Z", "PLANNED", false);
    UUID second = saveMission(mine, "2099-02-02T00:00:00Z", "PLANNED", false);
    saveMission(mine, "2099-02-03T00:00:00Z", "PLANNED", false);

    List<Mission> rows = upcoming(member, false, null, Set.of(mine.getId()), 2);

    assertThat(rows).extracting(Mission::getId).containsExactly(first, second);
  }

  /** A caller with no unit scope sees the organisation-wide upcoming missions. */
  @Test
  void aCallerWithoutAScopeSeesTheOrganisationWideMissions() {
    User member = newUser();
    OrgUnit foreign = newSquadron();
    UUID any = saveMission(foreign, "2099-02-01T00:00:00Z", "PLANNED", true);

    List<Mission> rows = upcoming(member, true, null, Set.of(), 50);

    assertThat(rows).extracting(Mission::getId).contains(any);
  }

  private List<Mission> upcoming(
      User member, boolean orgWide, UUID pin, Set<UUID> memberUnits, int limit) {
    return missionRepository.findUpcomingOwnOrSignedUp(
        LOWER_BOUND,
        STATUSES,
        orgWide,
        false,
        pin,
        memberUnits,
        member.getId(),
        true,
        PageRequest.of(0, limit));
  }

  private User newUser() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("upcoming-" + UUID.randomUUID());
    return userRepository.save(user);
  }

  private OrgUnit newSquadron() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    Squadron squadron = new Squadron();
    squadron.setName("Upcoming-" + tag);
    squadron.setShorthand("UP" + tag);
    return squadronRepository.save(squadron);
  }

  private UUID saveMission(OrgUnit owner, String start, String status, boolean isInternal) {
    Mission mission = new Mission();
    mission.setName("Upcoming-Mission-" + UUID.randomUUID());
    mission.setStatus(status);
    mission.setIsInternal(isInternal);
    mission.setOwningOrgUnit(owner);
    mission.setPlannedStartTime(Instant.parse(start));
    return missionRepository.save(mission).getId();
  }

  private void signUp(UUID missionId, User user) {
    MissionParticipant participant = new MissionParticipant();
    participant.setMission(missionRepository.getReferenceById(missionId));
    participant.setUser(user);
    participantRepository.save(participant);
  }
}
