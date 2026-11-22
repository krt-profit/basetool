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

package de.greluc.krt.profit.basetool.backend.operation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.identity.api.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holds the payout freeze of a settled operation to its exception for external names (V440,
 * REQ-MISSION-034): renaming or removing the name moves the paid mark with the payout key, while a
 * rename that would merge two payout rows and every other roster change stay frozen.
 */
@SpringBootTest
@Transactional
class SettledOperationExternalRenameTest {

  private static final Instant START = Instant.parse("2026-03-01T10:00:00Z");
  private static final Instant END = START.plus(60, ChronoUnit.MINUTES);

  @Autowired private OperationRepository operationRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private MissionParticipantRepository participantRepository;
  @Autowired private JdbcTemplate jdbc;

  private UUID operationId;

  @Test
  void renamingTheOnlyRowOfAnExternalMovesThePaidMark() {
    UUID mission = settledMission();
    UUID guest = external(mission, "Alpha Guest");
    paid("guest_Alpha Guest");

    rename(guest, "External 1");

    assertThat(paidKeys()).containsExactly("guest_External 1");
  }

  @Test
  void removingTheNameMovesTheMarkToTheKeyOfTheNamelessRow() {
    UUID mission = settledMission();
    UUID guest = external(mission, "Alpha Guest");
    paid("guest_Alpha Guest");

    rename(guest, null);

    assertThat(paidKeys()).containsExactly("deleted_" + guest);
  }

  @Test
  void renamingOneOfTwoRowsOfTheSameExternalKeepsBothKeysPaid() {
    UUID first = settledMission();
    UUID second = linkedMission();
    UUID renamed = external(first, "Alpha Guest");
    external(second, "Alpha Guest");
    paid("guest_Alpha Guest");

    rename(renamed, "External 1");

    assertThat(paidKeys()).containsExactlyInAnyOrder("guest_Alpha Guest", "guest_External 1");
  }

  @Test
  void aRenameOntoAnotherExternalsNameStaysFrozen() {
    UUID mission = settledMission();
    UUID guest = external(mission, "Alpha Guest");
    external(mission, "Beta Guest");
    paid("guest_Alpha Guest");

    assertThatThrownBy(() -> rename(guest, "Beta Guest"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("Operation payout inputs are frozen");
  }

  @Test
  void anAttendanceChangeOfAnExternalStaysFrozen() {
    UUID mission = settledMission();
    UUID guest = external(mission, "Alpha Guest");
    paid("guest_Alpha Guest");

    assertThatThrownBy(() ->
            jdbc.update(
                "UPDATE mission_participant SET guest_name = 'External 1', end_time = ?"
                    + " WHERE id = ?",
                Timestamp.from(END.minus(10, ChronoUnit.MINUTES)),
                guest))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("Operation payout inputs are frozen");
  }

  private void rename(UUID participant, String name) {
    jdbc.update("UPDATE mission_participant SET guest_name = ? WHERE id = ?", name, participant);
  }

  private UUID settledMission() {
    Operation operation = new Operation();
    operation.setName("Settled-Op-" + UUID.randomUUID());
    operation.setStatus(OperationStatus.COMPLETED);
    operationId = operationRepository.save(operation).getId();
    return linkedMission();
  }

  private UUID linkedMission() {
    Mission mission = new Mission();
    mission.setName("Settled-Mission-" + UUID.randomUUID());
    mission.setStatus("COMPLETED");
    mission.setOperationId(operationId);
    mission.setActualStartTime(START);
    mission.setActualEndTime(END);
    return missionRepository.saveAndFlush(mission).getId();
  }

  private UUID external(UUID missionId, String name) {
    MissionParticipant participant = new MissionParticipant();
    participant.setMission(missionRepository.getReferenceById(missionId));
    participant.setGuestName(name);
    participant.setStartTime(START);
    participant.setEndTime(END);
    participant.setPayoutPreference(PayoutPreference.PAYOUT);
    return participantRepository.saveAndFlush(participant).getId();
  }

  private void paid(String participantKey) {
    jdbc.update(
        "INSERT INTO operation_payout_status (id, operation_id, participant_key, paid_out,"
            + " paid_out_at, settlement_fee_rate, settlement_calculated_at)"
            + " VALUES (?, ?, ?, TRUE, now(), 0.005, now())",
        UUID.randomUUID(),
        operationId,
        participantKey);
  }

  private List<String> paidKeys() {
    return jdbc.queryForList(
        "SELECT participant_key FROM operation_payout_status WHERE operation_id = ? AND paid_out",
        String.class,
        operationId);
  }
}
