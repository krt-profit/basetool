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

import static org.junit.jupiter.api.Assertions.*;

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronDto;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class SquadronServiceTest {

  @Autowired private SquadronService squadronService;

  @Autowired private SquadronRepository squadronRepository;

  @Autowired private MissionRepository missionRepository;

  @Autowired private MissionParticipantRepository missionParticipantRepository;

  @Test
  void createSquadron_ShouldSaveShorthand() {
    Squadron s = new Squadron();
    s.setName("Test Squadron");
    s.setShorthand("TST");
    s.setDescription("Description");

    Squadron saved = squadronService.createSquadron(s);

    assertNotNull(saved.getId());
    assertEquals("TST", saved.getShorthand());
  }

  @Test
  void createSquadron_MissingShorthand_ShouldThrow() {
    Squadron s = new Squadron();
    s.setName("Incomplete");

    assertThrows(
        DataIntegrityViolationException.class,
        () -> {
          squadronService.createSquadron(s);
          squadronRepository.flush();
        });
  }

  @Test
  void updateSquadron_ShouldUpdateShorthand() {
    Squadron s = new Squadron();
    s.setName("Update Test");
    s.setShorthand("UPD");
    s = squadronService.createSquadron(s);

    SquadronDto update =
        new SquadronDto(
            s.getId(), "Updated Name", "NEW", "Updated Desc", true, true, false, s.getVersion());

    Squadron updated = squadronService.updateSquadron(s.getId(), update);

    assertEquals("NEW", updated.getShorthand());
    assertEquals("Updated Name", updated.getName());
  }

  @Test
  void setProfitEligible_flipsFlagAndPersists() {
    Squadron s = new Squadron();
    s.setName("Profit Toggle");
    s.setShorthand("PFT");
    s = squadronService.createSquadron(s);
    assertFalse(s.isProfitEligible());

    Squadron enabled = squadronService.setProfitEligible(s.getId(), true);
    assertTrue(enabled.isProfitEligible(), "Toggle must flip the flag on");
    assertEquals("PFT", enabled.getShorthand(), "Toggle must not touch the shorthand");

    Squadron disabled = squadronService.setProfitEligible(s.getId(), false);
    assertFalse(disabled.isProfitEligible(), "Toggle must flip the flag off again");
  }

  @Test
  void deleteSquadron_ShouldSetInactive() {
    Squadron squadron = new Squadron();
    squadron.setName("Active Squadron");
    squadron.setShorthand("ACT");
    squadron = squadronService.createSquadron(squadron);

    Mission mission = new Mission();
    mission.setName("Test Mission");
    mission.setStatus("PLANNED");
    mission.setPlannedStartTime(Instant.now());
    mission.setOwningOrgUnit(squadron);
    mission = missionRepository.save(mission);

    MissionParticipant participant = new MissionParticipant();
    participant.setMission(mission);
    participant.setGuestName("Test Guest");
    participant.setOrgUnits(java.util.List.of(squadron));
    missionParticipantRepository.save(participant);

    UUID squadronId = squadron.getId();
    squadronService.deleteSquadron(squadronId);

    Squadron updatedSquadron = squadronRepository.findById(squadronId).orElseThrow();
    assertFalse(updatedSquadron.isActive());
  }
}
