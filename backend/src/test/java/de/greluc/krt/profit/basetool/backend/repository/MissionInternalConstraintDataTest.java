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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the {@code ck_mission_internal_has_owning_org_unit} check constraint against PostgreSQL:
 * a mission without an owning org unit cannot be internal (REQ-MISSION-008).
 */
@SpringBootTest
@Transactional
class MissionInternalConstraintDataTest {

  @Autowired private MissionRepository missionRepository;
  @Autowired private SquadronRepository squadronRepository;

  /** An internal mission without an owning org unit is refused by the database. */
  @Test
  void anOwnerlessInternalMissionIsRefused() {
    Mission mission = newMission(true);

    assertThatThrownBy(() -> missionRepository.saveAndFlush(mission))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** An ownerless public mission and an owned internal mission are accepted. */
  @Test
  void anOwnerlessPublicAndAnOwnedInternalMissionAreAccepted() {
    Mission ownerlessPublic = newMission(false);
    Mission ownedInternal = newMission(true);
    String tag = UUID.randomUUID().toString().substring(0, 8);
    Squadron squadron = new Squadron();
    squadron.setName("Internal-" + tag);
    squadron.setShorthand("IN" + tag);
    ownedInternal.setOwningOrgUnit(squadronRepository.save(squadron));

    assertThat(missionRepository.saveAndFlush(ownerlessPublic).getId()).isNotNull();
    assertThat(missionRepository.saveAndFlush(ownedInternal).getId()).isNotNull();
  }

  private static Mission newMission(boolean isInternal) {
    Mission mission = new Mission();
    mission.setName("Constraint-Mission-" + UUID.randomUUID());
    mission.setStatus("PLANNED");
    mission.setIsInternal(isInternal);
    mission.setPlannedStartTime(Instant.parse("2099-01-01T00:00:00Z"));
    return mission;
  }
}
