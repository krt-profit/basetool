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

package de.greluc.krt.profit.basetool.backend.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.JobType;
import de.greluc.krt.profit.basetool.backend.model.JobTypeArchetype;
import de.greluc.krt.profit.basetool.backend.model.dto.JobTypeDto;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the job type embedded in a mission and the standalone {@link JobTypeMapper} both
 * publish the Einsatzleiter designation (REQ-MISSION-013).
 */
class MissionMapperJobTypeTest {

  private final MissionMapper mapper = new MissionMapperImpl(null, null, null, null);

  private static JobType jobType(boolean missionLead, boolean leadershipRole) {
    JobType jobType = new JobType();
    jobType.setId(UUID.randomUUID());
    jobType.setName("Einsatzleiter");
    jobType.setArchetype(JobTypeArchetype.MISSION);
    jobType.setMissionLead(missionLead);
    jobType.setLeadershipRole(leadershipRole);
    return jobType;
  }

  @Test
  void theMissionLeadDesignationReachesTheNestedDto() {
    JobTypeDto dto = mapper.toDto(jobType(true, true));

    assertThat(dto.isMissionLead()).isTrue();
    assertThat(dto.isLeadershipRole()).isTrue();
  }

  @Test
  void anOrdinaryJobTypeIsExplicitlyNotTheMissionLead() {
    JobTypeDto dto = mapper.toDto(jobType(false, false));

    assertThat(dto.isMissionLead()).isFalse();
    assertThat(dto.isLeadershipRole()).isFalse();
  }

  @Test
  void theNestedMappingAgreesWithTheStandaloneJobTypeMapper() {
    JobType jobType = jobType(true, true);

    assertThat(mapper.toDto(jobType)).isEqualTo(new JobTypeMapperImpl().toDto(jobType));
  }
}
