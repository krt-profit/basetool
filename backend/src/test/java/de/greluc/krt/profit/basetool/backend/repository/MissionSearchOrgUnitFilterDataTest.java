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

import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves against PostgreSQL that the mission search narrows to one owning org unit and that the
 * caller's visibility scope still applies on top of that filter (REQ-MISSION-012).
 */
@SpringBootTest
@Transactional
class MissionSearchOrgUnitFilterDataTest {

  private static final String TAG = "Ou" + UUID.randomUUID().toString().substring(0, 8);

  @Autowired private MissionRepository missionRepository;
  @Autowired private SquadronRepository squadronRepository;

  @Test
  void orgUnitFilter_narrowsToTheMissionsOfThatUnit() {
    OrgUnit alpha = saveSquadron("A");
    OrgUnit beta = saveSquadron("B");
    saveMission(alpha, TAG + " Alpha", false);
    saveMission(beta, TAG + " Beta", false);

    assertThat(names(true, Set.of(), alpha.getId())).containsExactly(TAG + " Alpha");
    assertThat(names(true, Set.of(), beta.getId())).containsExactly(TAG + " Beta");
    assertThat(names(true, Set.of(), null))
        .containsExactlyInAnyOrder(TAG + " Alpha", TAG + " Beta");
  }

  @Test
  void orgUnitFilter_keepsTheCallersVisibilityScope() {
    OrgUnit alpha = saveSquadron("A");
    OrgUnit beta = saveSquadron("B");
    saveMission(beta, TAG + " Beta public", false);
    saveMission(beta, TAG + " Beta internal", true);

    assertThat(names(false, Set.of(alpha.getId()), beta.getId()))
        .containsExactly(TAG + " Beta public");
    assertThat(names(false, Set.of(beta.getId()), beta.getId()))
        .containsExactlyInAnyOrder(TAG + " Beta public", TAG + " Beta internal");
  }

  private List<String> names(
      boolean adminAllScope, Set<UUID> memberUnits, @Nullable UUID orgUnitId) {
    return missionRepository
        .searchMissions(
            TAG,
            null,
            null,
            List.of("PLANNED"),
            null,
            null,
            orgUnitId,
            adminAllScope,
            null,
            memberUnits.isEmpty() ? Set.of(UUID.randomUUID()) : memberUnits,
            true,
            PageRequest.of(0, 50))
        .getContent()
        .stream()
        .map(Mission::getName)
        .toList();
  }

  private OrgUnit saveSquadron(String suffix) {
    Squadron squadron = new Squadron();
    squadron.setName("OuSq" + suffix + "-" + TAG);
    squadron.setShorthand(suffix + UUID.randomUUID().toString().substring(0, 6));
    return squadronRepository.save(squadron);
  }

  private void saveMission(OrgUnit owner, String name, boolean internal) {
    Mission mission = new Mission();
    mission.setName(name);
    mission.setStatus("PLANNED");
    mission.setIsInternal(internal);
    mission.setOwningOrgUnit(owner);
    missionRepository.save(mission);
  }
}
