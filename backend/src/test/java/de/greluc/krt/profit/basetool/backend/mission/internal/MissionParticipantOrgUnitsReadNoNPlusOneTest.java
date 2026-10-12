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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.mapper.MissionMapper;
import de.greluc.krt.profit.basetool.backend.mission.web.MissionController;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionParticipantDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitReferenceDto;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies against real Postgres that every read of a mission roster that carries the participants'
 * org units costs the same number of SQL statements whether the mission holds a few or many
 * participants, and that each participant DTO lists exactly its stamped org units (REQ-DATA-003).
 *
 * <p>The reads run through the {@link MissionController} bean with no surrounding test transaction,
 * as in production with open-in-view disabled.
 */
@SpringBootTest
class MissionParticipantOrgUnitsReadNoNPlusOneTest {

  private static final int FEW = 3;

  private static final int MANY = 25;

  private static final int DETAIL_BOUND = 8;

  private static final int ROSTER_BOUND = 3;

  private static final int REDACTION_BOUND = 7;

  private static final int UPDATE_BOUND = 9;

  private static final int PAYOUT_BOUND = 7;

  @Autowired private MissionController missionController;
  @Autowired private MissionMapper missionMapper;
  @Autowired private MissionPeerRedactor missionPeerRedactor;
  @Autowired private MissionRepository missionRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private SpecialCommandRepository specialCommandRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrgUnitMembershipRepository membershipRepository;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private PlatformTransactionManager transactionManager;

  private User admin;

  private Squadron staffel;

  private SpecialCommand command;

  private final List<UUID> missionIds = new ArrayList<>();

  @BeforeEach
  void signInAsAdmin() {
    String tag = UUID.randomUUID().toString().substring(0, 6);
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.executeWithoutResult(
        _ -> {
          User user = new User();
          user.setId(UUID.randomUUID());
          user.setUsername("orgunits-admin-" + tag);
          admin = userRepository.save(user);
          OrgUnitMembership membership = new OrgUnitMembership();
          membership.setId(new OrgUnitMembershipId(admin.getId(), Squadron.IRIDIUM_ID));
          membership.setUser(admin);
          membership.setKind(OrgUnitKind.SQUADRON);
          membership.setJoinedAt(Instant.now());
          membershipRepository.save(membership);
          Squadron squadron = new Squadron();
          squadron.setName("Roster Staffel " + tag);
          squadron.setShorthand("R" + tag);
          staffel = squadronRepository.save(squadron);
          SpecialCommand specialCommand = new SpecialCommand();
          specialCommand.setName("Roster Kommando " + tag);
          specialCommand.setShorthand("K" + tag);
          command = specialCommandRepository.save(specialCommand);
        });
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                Jwt.withTokenValue("participant-org-units")
                    .header("alg", "none")
                    .subject(admin.getId().toString())
                    .build(),
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
  }

  @AfterEach
  void signOutAndCleanUp() {
    SecurityContextHolder.clearContext();
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.executeWithoutResult(
        _ -> {
          for (UUID id : missionIds) {
            missionRepository.deleteById(id);
          }
          missionRepository.flush();
          membershipRepository.deleteById(
              new OrgUnitMembershipId(admin.getId(), Squadron.IRIDIUM_ID));
          membershipRepository.flush();
          userRepository.deleteById(admin.getId());
          squadronRepository.deleteById(staffel.getId());
          specialCommandRepository.deleteById(command.getId());
        });
    missionIds.clear();
  }

  @Test
  void theMissionDetailListsEachParticipantsOrgUnitsWithAStatementCountIndependentOfItsSize() {
    UUID few = seed(FEW);
    UUID many = seed(MANY);
    missionController.getMissionById(few);
    Statistics stats = statistics();

    stats.clear();
    MissionDto fewDto = missionController.getMissionById(few);
    long fewStatements = stats.getPrepareStatementCount();
    stats.clear();
    MissionDto manyDto = missionController.getMissionById(many);
    long manyStatements = stats.getPrepareStatementCount();
    report("mission detail", fewStatements, manyStatements);

    assertThat(fewDto.participants()).hasSize(FEW);
    assertThat(manyDto.participants()).hasSize(MANY);
    manyDto.participants().forEach(this::assertStampedOrgUnits);
    assertThat(manyStatements)
        .as("detail statements for " + MANY + " participants against " + FEW + " (N+1)")
        .isEqualTo(fewStatements)
        .isLessThanOrEqualTo(DETAIL_BOUND);
  }

  @Test
  void theUnassignedRosterListsEachParticipantsOrgUnitsWithAStatementCountIndependentOfItsSize() {
    UUID few = seed(FEW);
    UUID many = seed(MANY);
    missionController.getUnassignedParticipants(few);
    Statistics stats = statistics();

    stats.clear();
    List<MissionParticipantDto> fewRows = missionController.getUnassignedParticipants(few);
    long fewStatements = stats.getPrepareStatementCount();
    stats.clear();
    List<MissionParticipantDto> manyRows = missionController.getUnassignedParticipants(many);
    long manyStatements = stats.getPrepareStatementCount();
    report("unassigned roster", fewStatements, manyStatements);

    assertThat(fewRows).hasSize(FEW);
    assertThat(manyRows).hasSize(MANY);
    manyRows.forEach(this::assertStampedOrgUnits);
    assertThat(manyStatements)
        .as("roster statements for " + MANY + " participants against " + FEW + " (N+1)")
        .isEqualTo(fewStatements)
        .isLessThanOrEqualTo(ROSTER_BOUND);
  }

  @Test
  void thePeerRedactionKeepsTheOrgUnitsAndNeedsNoFurtherStatementsPerParticipant() {
    UUID few = seed(FEW);
    UUID many = seed(MANY);
    Statistics stats = statistics();

    long fewStatements = redactedStatements(few, stats);
    long manyStatements = redactedStatements(many, stats);
    report("peer redaction", fewStatements, manyStatements);

    MissionDto redacted = redactedDetail(many);
    assertThat(redacted.participants()).hasSize(MANY);
    redacted.participants().forEach(this::assertStampedOrgUnits);
    assertThat(manyStatements)
        .as("peer-redacted detail statements for " + MANY + " against " + FEW + " (N+1)")
        .isEqualTo(fewStatements)
        .isLessThanOrEqualTo(REDACTION_BOUND);
  }

  @Test
  void updatingAParticipantRestampsItsOrgUnitsWithAStatementCountIndependentOfTheRosterSize() {
    UUID few = seed(FEW);
    UUID many = seed(MANY);
    updateOrgUnits(firstParticipantOf(few), command.getId());
    updateOrgUnits(firstParticipantOf(many), command.getId());
    Target fewTarget = firstParticipantOf(few);
    Target manyTarget = firstParticipantOf(many);
    Statistics stats = statistics();

    stats.clear();
    MissionParticipantDto fewDto = updateOrgUnits(fewTarget, staffel.getId());
    long fewStatements = stats.getPrepareStatementCount();
    stats.clear();
    MissionParticipantDto manyDto = updateOrgUnits(manyTarget, staffel.getId());
    long manyStatements = stats.getPrepareStatementCount();
    report("participant update", fewStatements, manyStatements);

    assertThat(fewDto.orgUnits())
        .extracting(OrgUnitReferenceDto::id)
        .containsExactly(staffel.getId());
    assertThat(manyDto.orgUnits())
        .extracting(OrgUnitReferenceDto::id)
        .containsExactly(staffel.getId());
    assertThat(manyStatements)
        .as("update statements on a roster of " + MANY + " against " + FEW + " (N+1)")
        .isEqualTo(fewStatements)
        .isLessThanOrEqualTo(UPDATE_BOUND);
  }

  @Test
  void aSingleParticipantEndpointListsItsOrgUnitsWithAStatementCountIndependentOfTheRosterSize() {
    UUID few = seed(FEW);
    UUID many = seed(MANY);
    Target fewTarget = firstParticipantOf(few);
    Target manyTarget = firstParticipantOf(many);
    changePayout(fewTarget);
    changePayout(manyTarget);
    Statistics stats = statistics();

    stats.clear();
    MissionParticipantDto fewDto = changePayout(fewTarget);
    long fewStatements = stats.getPrepareStatementCount();
    stats.clear();
    MissionParticipantDto manyDto = changePayout(manyTarget);
    long manyStatements = stats.getPrepareStatementCount();
    report("payout preference", fewStatements, manyStatements);

    assertThat(fewDto.orgUnits()).isNotEmpty();
    assertStampedOrgUnits(fewDto);
    assertStampedOrgUnits(manyDto);
    assertThat(manyStatements)
        .as("payout statements on a roster of " + MANY + " against " + FEW + " (N+1)")
        .isEqualTo(fewStatements)
        .isLessThanOrEqualTo(PAYOUT_BOUND);
  }

  private MissionParticipantDto changePayout(Target target) {
    return missionController.updatePayoutPreferenceSlim(
        target.missionId(),
        target.participantId(),
        new UpdatePayoutPreferenceRequest(PayoutPreference.DONATE));
  }

  private MissionParticipantDto updateOrgUnits(Target target, UUID orgUnitId) {
    return missionController.updateParticipantSlim(
        target.missionId(),
        target.participantId(),
        new UpdateParticipantRequest(
            null, null, "restamped", null, null, null, List.of(orgUnitId), null, target.version()),
        SecurityContextHolder.getContext().getAuthentication());
  }

  private Target firstParticipantOf(UUID missionId) {
    return inReadOnlyTransaction(
        () -> {
          MissionParticipant first =
              missionRepository.findById(missionId).orElseThrow().getParticipants().stream()
                  .filter(participant -> "Guest 0".equals(participant.getGuestName()))
                  .findFirst()
                  .orElseThrow();
          return new Target(missionId, first.getId(), first.getVersion());
        });
  }

  private record Target(UUID missionId, UUID participantId, Long version) {}

  private long redactedStatements(UUID missionId, Statistics stats) {
    stats.clear();
    redactedDetail(missionId);
    return stats.getPrepareStatementCount();
  }

  private MissionDto redactedDetail(UUID missionId) {
    return inReadOnlyTransaction(
        () ->
            missionPeerRedactor.cleanupMissionForPeer(
                missionMapper.toDto(missionRepository.findById(missionId).orElseThrow())));
  }

  private <T> T inReadOnlyTransaction(Supplier<T> work) {
    TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
    readOnly.setReadOnly(true);
    return readOnly.execute(_ -> work.get());
  }

  private void assertStampedOrgUnits(MissionParticipantDto participant) {
    String guestName = participant.guestName();
    boolean even = (guestName.charAt(guestName.length() - 1) - '0') % 2 == 0;
    List<UUID> expected =
        even ? List.of(staffel.getId(), command.getId()) : List.of(command.getId());
    assertThat(participant.orgUnits())
        .extracting(OrgUnitReferenceDto::id)
        .containsExactlyElementsOf(expected);
    assertThat(participant.orgUnits())
        .extracting(OrgUnitReferenceDto::kind)
        .containsExactlyElementsOf(
            even
                ? List.of(OrgUnitKind.SQUADRON, OrgUnitKind.SPECIAL_COMMAND)
                : List.of(OrgUnitKind.SPECIAL_COMMAND));
  }

  private Statistics statistics() {
    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);
    return stats;
  }

  private void report(String path, long few, long many) {
    System.out.println(
        "[D-20] " + path + ": " + FEW + "=" + few + " statements, " + MANY + "=" + many);
  }

  private UUID seed(int participants) {
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    UUID id =
        tx.execute(
            _ -> {
              Mission mission = new Mission();
              mission.setName("Roster " + UUID.randomUUID());
              mission.setStatus("PLANNED");
              mission.setOwningOrgUnit(
                  squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow());
              for (int i = 0; i < participants; i++) {
                MissionParticipant participant = new MissionParticipant();
                participant.setMission(mission);
                participant.setGuestName("Guest " + i);
                participant.setOrgUnits(i % 2 == 0 ? List.of(staffel, command) : List.of(command));
                mission.getParticipants().add(participant);
              }
              return missionRepository.save(mission).getId();
            });
    missionIds.add(id);
    return id;
  }
}
