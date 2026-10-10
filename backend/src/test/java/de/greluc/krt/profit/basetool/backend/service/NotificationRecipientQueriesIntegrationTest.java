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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeInstallation;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeInstallationRepository;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.OrgRelativeRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The recipient queries behind the mission, exchange-client and unit-leadership selectors against
 * Postgres (REQ-NOTIF-024).
 */
@SpringBootTest
class NotificationRecipientQueriesIntegrationTest {

  private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000a0a1");
  private static final UUID MANAGER = UUID.fromString("00000000-0000-0000-0000-00000000a0a2");
  private static final UUID CHECKED_IN = UUID.fromString("00000000-0000-0000-0000-00000000a0a3");
  private static final UUID WAITING = UUID.fromString("00000000-0000-0000-0000-00000000a0a4");
  private static final UUID STAFFELLEITER = UUID.fromString("00000000-0000-0000-0000-00000000a0a5");
  private static final UUID PLAIN_MEMBER = UUID.fromString("00000000-0000-0000-0000-00000000a0a6");
  private static final String CLIENT_ID = "queries-it-client";

  @Autowired private RecipientResolutionService recipientResolutionService;
  @Autowired private UserRepository userRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private MissionParticipantRepository missionParticipantRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Autowired private ExchangeClientRepository exchangeClientRepository;
  @Autowired private ExchangeInstallationRepository exchangeInstallationRepository;

  private UUID missionId;
  private UUID clientId;

  @BeforeEach
  void seed() {
    Set<User> users = new HashSet<>();
    for (UUID id : Set.of(OWNER, MANAGER, CHECKED_IN, WAITING, STAFFELLEITER, PLAIN_MEMBER)) {
      users.add(user(id));
    }
    User owner = users.stream().filter(u -> OWNER.equals(u.getId())).findFirst().orElseThrow();
    User manager = users.stream().filter(u -> MANAGER.equals(u.getId())).findFirst().orElseThrow();

    Mission mission = new Mission();
    mission.setName("Recipient queries " + UUID.randomUUID());
    mission.setStatus("PLANNED");
    mission.setOwner(owner);
    mission.getManagers().add(manager);
    missionId = missionRepository.saveAndFlush(mission).getId();
    participant(mission, userById(users, CHECKED_IN), Instant.now());
    participant(mission, userById(users, WAITING), null);
    MissionParticipant guest = new MissionParticipant();
    guest.setMission(mission);
    guest.setGuestName("Guest");
    missionParticipantRepository.saveAndFlush(guest);

    membership(userById(users, STAFFELLEITER), MembershipRole.STAFFELLEITER);
    membership(userById(users, PLAIN_MEMBER), MembershipRole.MEMBER);

    ExchangeClient client = new ExchangeClient();
    client.setClientId(CLIENT_ID);
    client.setDisplayName("Queries client");
    client.setStatus(ExchangeClientStatus.ACTIVE);
    client.setCapabilities(EnumSet.of(ExchangeCapability.CONNECT));
    clientId = exchangeClientRepository.saveAndFlush(client).getId();
    installation(client, userById(users, WAITING), "L".repeat(43), null);
    installation(client, userById(users, CHECKED_IN), "R".repeat(43), Instant.now());
  }

  @AfterEach
  void cleanUp() {
    exchangeClientRepository.findAll().stream()
        .filter(c -> CLIENT_ID.equals(c.getClientId()))
        .forEach(exchangeClientRepository::delete);
    missionRepository.deleteById(missionId);
    orgUnitMembershipRepository.deleteAll(
        orgUnitMembershipRepository.findAllByIdUserId(STAFFELLEITER));
    orgUnitMembershipRepository.deleteAll(
        orgUnitMembershipRepository.findAllByIdUserId(PLAIN_MEMBER));
    userRepository.deleteAllById(
        Set.of(OWNER, MANAGER, CHECKED_IN, WAITING, STAFFELLEITER, PLAIN_MEMBER));
  }

  @Test
  void missionParticipantsAreTheRegisteredSignUpsWithoutGuests() {
    assertThat(recipientResolutionService.resolveMissionParticipants(missionId, false))
        .containsExactlyInAnyOrder(CHECKED_IN, WAITING);
  }

  @Test
  void missionParticipantsCanBeNarrowedToTheNotCheckedIn() {
    assertThat(recipientResolutionService.resolveMissionParticipants(missionId, true))
        .containsExactly(WAITING);
  }

  @Test
  void missionLeadershipIsTheOwnerAndTheCoManagers() {
    assertThat(recipientResolutionService.resolveMissionLeadership(missionId))
        .containsExactlyInAnyOrder(OWNER, MANAGER);
  }

  @Test
  void exchangeClientHoldersSkipRevokedInstallations() {
    assertThat(recipientResolutionService.resolveExchangeClientHolders(clientId))
        .containsExactly(WAITING);
    assertThat(recipientResolutionService.resolveExchangeClientHolders(null)).contains(WAITING);
    assertThat(recipientResolutionService.resolveExchangeClientHolders(null))
        .doesNotContain(CHECKED_IN);
  }

  @Test
  void unitLeadershipIsEverySeatHolderAndNotThePlainMembers() {
    assertThat(
            recipientResolutionService.resolveOrgRelative(
                OrgRelativeRole.UNIT_LEADERSHIP, Squadron.IRIDIUM_ID))
        .contains(STAFFELLEITER)
        .doesNotContain(PLAIN_MEMBER);
  }

  private User user(UUID id) {
    User user = new User();
    user.setId(id);
    user.setUsername("queries-it-" + id);
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    return userRepository.saveAndFlush(user);
  }

  private static User userById(Set<User> users, UUID id) {
    return users.stream().filter(u -> id.equals(u.getId())).findFirst().orElseThrow();
  }

  private void participant(Mission mission, User user, Instant startTime) {
    MissionParticipant participant = new MissionParticipant();
    participant.setMission(mission);
    participant.setUser(user);
    participant.setStartTime(startTime);
    missionParticipantRepository.saveAndFlush(participant);
  }

  private void membership(User user, MembershipRole role) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user.getId(), Squadron.IRIDIUM_ID));
    membership.setUser(user);
    membership.setRole(role);
    membership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.saveAndFlush(membership);
  }

  private void installation(
      ExchangeClient client, User user, String thumbprint, Instant revokedAt) {
    ExchangeInstallation installation = new ExchangeInstallation();
    installation.setClient(client);
    installation.setUser(user);
    installation.setKeyThumbprint(thumbprint);
    installation.setFirstSeenAt(Instant.now());
    installation.setLastSeenAt(Instant.now());
    installation.setRevokedAt(revokedAt);
    exchangeInstallationRepository.saveAndFlush(installation);
  }
}
