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

package de.greluc.krt.profit.basetool.backend.privacy.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.identity.api.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.identity.internal.ApprovalReasonRetention;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.mission.internal.ExternalNameRetention;
import de.greluc.krt.profit.basetool.backend.operation.internal.Operation;
import de.greluc.krt.profit.basetool.backend.operation.internal.OperationRepository;
import de.greluc.krt.profit.basetool.backend.operation.internal.OperationStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** The name retention's areas against Postgres (REQ-DATA-047). */
@SpringBootTest
@Transactional
class NameRetentionIntegrationTest {

  private static final Instant CUTOFF = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant OLD = CUTOFF.minus(30, ChronoUnit.DAYS);
  private static final Instant RECENT = CUTOFF.plus(30, ChronoUnit.DAYS);

  @Autowired private ApprovalReasonRetention approvalReasonRetention;
  @Autowired private ExternalNameRetention externalNameRetention;
  @Autowired private UserRepository userRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private MissionParticipantRepository participantRepository;
  @Autowired private OperationRepository operationRepository;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void approvalReasonsOlderThanTheCutoffGo() {
    UUID user = user();
    UUID old = approvalEvent(user, OLD);
    UUID recent = approvalEvent(user, RECENT);

    assertThat(approvalReasonRetention.removeNamesBefore(CUTOFF)).isGreaterThanOrEqualTo(1);

    assertThat(reason(old)).isNull();
    assertThat(reason(recent)).isEqualTo("Reason text");
  }

  @Test
  void externalNamesOfMissionsEndedBeforeTheCutoffGo() {
    UUID oldMission = mission(OLD, null);
    UUID recentMission = mission(RECENT, null);
    UUID oldGuest = external(oldMission, "Old Guest");
    UUID recentGuest = external(recentMission, "Recent Guest");
    jdbc.update("UPDATE mission SET party_lead_guest_name = 'Old Lead' WHERE id = ?", oldMission);

    assertThat(externalNameRetention.removeNamesBefore(CUTOFF)).isGreaterThanOrEqualTo(2);

    assertThat(guestName(oldGuest)).isNull();
    assertThat(guestName(recentGuest)).isEqualTo("Recent Guest");
    assertThat(
            jdbc.queryForObject(
                "SELECT party_lead_guest_name FROM mission WHERE id = ?", String.class, oldMission))
        .isNull();
  }

  @Test
  void aPaidMarkOnASettledOperationFollowsTheRemovedNames() {
    Operation operation = new Operation();
    operation.setName("Retention-Op-" + UUID.randomUUID());
    operation.setStatus(OperationStatus.COMPLETED);
    UUID operationId = operationRepository.save(operation).getId();
    UUID first = external(mission(OLD, operationId), "Settled Guest");
    UUID second = external(mission(OLD, operationId), "Settled Guest");
    jdbc.update(
        "INSERT INTO operation_payout_status (id, operation_id, participant_key, paid_out,"
            + " paid_out_at, settlement_fee_rate, settlement_calculated_at)"
            + " VALUES (?, ?, 'guest_Settled Guest', TRUE, now(), 0.005, now())",
        UUID.randomUUID(),
        operationId);

    externalNameRetention.removeNamesBefore(CUTOFF);

    assertThat(guestName(first)).isNull();
    assertThat(guestName(second)).isNull();
    assertThat(
            jdbc.queryForList(
                "SELECT participant_key FROM operation_payout_status"
                    + " WHERE operation_id = ? AND paid_out",
                String.class,
                operationId))
        .isNotEmpty()
        .allSatisfy(key -> assertThat(key).startsWith("deleted_"));
  }

  private UUID user() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("retention-" + UUID.randomUUID());
    return userRepository.saveAndFlush(user).getId();
  }

  private UUID approvalEvent(UUID user, Instant at) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO user_approval_event (id, user_id, decision, reason, created_at, updated_at,"
            + " version) VALUES (?, ?, 'APPROVED', 'Reason text', ?, ?, 0)",
        id,
        user,
        Timestamp.from(at),
        Timestamp.from(at));
    return id;
  }

  private String reason(UUID event) {
    return jdbc.queryForObject(
        "SELECT reason FROM user_approval_event WHERE id = ?", String.class, event);
  }

  private UUID mission(Instant end, UUID operationId) {
    Mission mission = new Mission();
    mission.setName("Retention-Mission-" + UUID.randomUUID());
    mission.setStatus("COMPLETED");
    mission.setOperationId(operationId);
    mission.setActualStartTime(end.minus(1, ChronoUnit.HOURS));
    mission.setActualEndTime(end);
    return missionRepository.saveAndFlush(mission).getId();
  }

  private UUID external(UUID missionId, String name) {
    Mission mission = missionRepository.getReferenceById(missionId);
    MissionParticipant participant = new MissionParticipant();
    participant.setMission(mission);
    participant.setGuestName(name);
    participant.setStartTime(mission.getActualStartTime());
    participant.setEndTime(mission.getActualEndTime());
    participant.setPayoutPreference(PayoutPreference.PAYOUT);
    return participantRepository.saveAndFlush(participant).getId();
  }

  private String guestName(UUID participant) {
    return jdbc.queryForObject(
        "SELECT guest_name FROM mission_participant WHERE id = ?", String.class, participant);
  }
}
