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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.mapper.JobTypeMapper;
import de.greluc.krt.profit.basetool.backend.model.JobType;
import de.greluc.krt.profit.basetool.backend.model.JobTypeArchetype;
import de.greluc.krt.profit.basetool.backend.model.dto.JobTypeDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.service.JobTypeService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/** Pure-method unit tests for {@link JobTypeController}. */
@ExtendWith(MockitoExtension.class)
class JobTypeControllerTest {

  @Mock private JobTypeService service;
  @Mock private JobTypeMapper mapper;

  @InjectMocks private JobTypeController controller;

  @Test
  void getAll_forwardsArchetypeFilterAndIncludeInactive() {
    when(service.getJobTypes(eq(JobTypeArchetype.CREW), any(Pageable.class), eq(true)))
        .thenReturn(new PageImpl<>(List.of()));

    controller.getAllJobTypes(JobTypeArchetype.CREW, null, null, null, true);

    verify(service).getJobTypes(eq(JobTypeArchetype.CREW), any(Pageable.class), eq(true));
  }

  @Test
  void getAll_nullArchetype_andIncludeInactiveFalse_isForwarded() {
    when(service.getJobTypes(eq(null), any(Pageable.class), eq(false)))
        .thenReturn(new PageImpl<>(List.of()));

    controller.getAllJobTypes(null, null, null, null, false);

    verify(service).getJobTypes(eq(null), any(Pageable.class), eq(false));
  }

  @Test
  void getAll_wrapsServicePageIntoPageResponse() {
    JobType entity = new JobType();
    JobTypeDto dto =
        new JobTypeDto(
            UUID.randomUUID(),
            "Pilot",
            "desc",
            JobTypeArchetype.CREW,
            null,
            true,
            false,
            false,
            1L);
    when(service.getJobTypes(any(), any(Pageable.class), eq(false)))
        .thenReturn(new PageImpl<>(List.of(entity)));
    when(mapper.toDto(entity)).thenReturn(dto);

    PageResponse<JobTypeDto> resp = controller.getAllJobTypes(null, 0, 50, null, false);

    assertEquals(1, resp.totalElements());
    assertSame(dto, resp.content().getFirst());
  }

  @Test
  void create_mapsDtoToEntityViaMapperAndDelegates() {
    JobTypeDto request =
        new JobTypeDto(null, "Pilot", null, JobTypeArchetype.CREW, null, true, false, false, null);
    JobType entity = new JobType();
    JobType persisted = new JobType();
    JobTypeDto response =
        new JobTypeDto(
            UUID.randomUUID(), "Pilot", null, JobTypeArchetype.CREW, null, true, false, false, 1L);

    when(mapper.toEntity(request)).thenReturn(entity);
    when(service.createJobType(entity)).thenReturn(persisted);
    when(mapper.toDto(persisted)).thenReturn(response);

    JobTypeDto result = controller.createJobType(request);

    assertSame(response, result);
    verify(service).createJobType(entity);
  }

  @Test
  void update_passesIdAndDtoDirectly_noMapperOnInput() {
    UUID id = UUID.randomUUID();
    JobTypeDto request =
        new JobTypeDto(id, "Renamed", null, JobTypeArchetype.CREW, null, true, true, false, 4L);
    JobType updated = new JobType();
    JobTypeDto response =
        new JobTypeDto(id, "Renamed", null, JobTypeArchetype.CREW, null, true, true, false, 5L);

    when(service.updateJobType(id, request)).thenReturn(updated);
    when(mapper.toDto(updated)).thenReturn(response);

    JobTypeDto result = controller.updateJobType(id, request);

    assertSame(response, result);
    verify(service).updateJobType(id, request);
    verify(mapper, never()).toEntity(any(JobTypeDto.class));
  }

  @Test
  void delete_delegatesIdToService() {
    UUID id = UUID.randomUUID();

    controller.deleteJobType(id);

    verify(service).deleteJobType(id);
  }

  @Test
  void activate_delegatesIdToService() {
    UUID id = UUID.randomUUID();

    controller.activateJobType(id);

    verify(service).activateJobType(id);
  }
}
