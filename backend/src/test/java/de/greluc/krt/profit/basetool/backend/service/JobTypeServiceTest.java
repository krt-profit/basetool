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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.JobType;
import de.greluc.krt.profit.basetool.backend.model.JobTypeArchetype;
import de.greluc.krt.profit.basetool.backend.model.dto.JobTypeDto;
import de.greluc.krt.profit.basetool.backend.repository.JobTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the "Einsatzleiter" (mission-lead) designation logic on {@link JobTypeService}
 * (REQ-MISSION-013): designating a job type clears any prior holder so at most one type is the
 * Einsatzleiter, and only a MISSION leadership role may be designated.
 */
@ExtendWith(MockitoExtension.class)
class JobTypeServiceTest {

  @Mock private JobTypeRepository jobTypeRepository;
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @InjectMocks private JobTypeService jobTypeService;

  private static JobType missionLeadership() {
    JobType jt = new JobType();
    jt.setName("Einsatzleiter");
    jt.setArchetype(JobTypeArchetype.MISSION);
    jt.setLeadershipRole(true);
    return jt;
  }

  @Test
  void createJobType_designatingMissionLead_clearsThePriorHolder() {
    JobType prior = missionLeadership();
    prior.setId(UUID.randomUUID());
    prior.setName("Alt-Einsatzleiter");
    prior.setMissionLead(true);

    JobType fresh = missionLeadership();
    fresh.setMissionLead(true);

    when(jobTypeRepository.existsByNameIgnoreCase("Einsatzleiter")).thenReturn(false);
    when(jobTypeRepository.findAllMissionLead()).thenReturn(List.of(prior));
    when(jobTypeRepository.save(any(JobType.class))).thenAnswer(i -> i.getArgument(0));

    JobType saved = jobTypeService.createJobType(fresh);

    assertTrue(saved.isMissionLead(), "the new type is the Einsatzleiter");
    assertFalse(prior.isMissionLead(), "the prior holder was cleared");
    verify(jobTypeRepository).save(prior);
  }

  @Test
  void updateJobType_designatingNonLeadership_throws() {
    UUID id = UUID.randomUUID();
    JobType existing = new JobType();
    existing.setId(id);
    existing.setVersion(0L);

    when(jobTypeRepository.existsByNameIgnoreCaseAndIdNot("Sammler", id)).thenReturn(false);
    when(jobTypeRepository.findById(id)).thenReturn(Optional.of(existing));

    // archetype MISSION but NOT a leadership role + isMissionLead requested -> rejected.
    JobTypeDto dto =
        new JobTypeDto(id, "Sammler", null, JobTypeArchetype.MISSION, null, true, false, true, 0L);

    assertThrows(IllegalArgumentException.class, () -> jobTypeService.updateJobType(id, dto));
  }

  @Test
  void updateJobType_clearingMissionLead_setsFlagFalse() {
    UUID id = UUID.randomUUID();
    JobType existing = missionLeadership();
    existing.setId(id);
    existing.setVersion(0L);
    existing.setMissionLead(true);

    when(jobTypeRepository.existsByNameIgnoreCaseAndIdNot("Einsatzleiter", id)).thenReturn(false);
    when(jobTypeRepository.findById(id)).thenReturn(Optional.of(existing));
    when(jobTypeRepository.save(any(JobType.class))).thenAnswer(i -> i.getArgument(0));

    JobTypeDto dto =
        new JobTypeDto(
            id, "Einsatzleiter", null, JobTypeArchetype.MISSION, null, true, true, false, 0L);

    JobType saved = jobTypeService.updateJobType(id, dto);

    assertFalse(saved.isMissionLead(), "the designation was cleared");
  }
}
