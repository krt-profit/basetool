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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.OrgChartPositionType;
import de.greluc.krt.profit.basetool.backend.model.dto.AreaLeadershipDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgChartDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgChartPositionCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgChartPositionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgChartPositionUpdateRequest;
import de.greluc.krt.profit.basetool.backend.service.OrgChartReadService;
import de.greluc.krt.profit.basetool.backend.service.OrgChartService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure-method unit tests for {@link OrgChartController}. Verifies that each endpoint delegates to
 * {@link OrgChartService} and returns its result unchanged; Spring-MVC binding and the
 * {@code @PreAuthorize} gates (GET = authenticated, writes = ADMIN) are covered by the security/MVC
 * integration layer, not here.
 */
@ExtendWith(MockitoExtension.class)
class OrgChartControllerTest {

  @Mock private OrgChartService orgChartService;
  @Mock private OrgChartReadService orgChartReadService;

  @InjectMocks private OrgChartController controller;

  @Test
  void getOrgChart_delegatesToService() {
    OrgChartDto chart =
        new OrgChartDto(
            null,
            List.of(),
            new AreaLeadershipDto(null, List.of(), List.of(), List.of()),
            List.of(),
            List.of());
    when(orgChartReadService.getOrgChart()).thenReturn(chart);

    assertSame(chart, controller.getOrgChart());
    verify(orgChartReadService).getOrgChart();
  }

  @Test
  void createPosition_delegatesRequestToService() {
    OrgChartPositionCreateRequest request =
        new OrgChartPositionCreateRequest(
            OrgChartPositionType.AREA_COORDINATOR, null, UUID.randomUUID(), null, null, null, null);
    OrgChartPositionDto response =
        new OrgChartPositionDto(
            UUID.randomUUID(),
            OrgChartPositionType.AREA_COORDINATOR,
            null,
            request.userId(),
            "Coordinator",
            null,
            null,
            null,
            0,
            0L);
    when(orgChartService.createPosition(request)).thenReturn(response);

    assertSame(response, controller.createPosition(request));
    verify(orgChartService).createPosition(request);
  }

  @Test
  void updatePosition_delegatesIdAndRequestToService() {
    UUID id = UUID.randomUUID();
    OrgChartPositionUpdateRequest request =
        new OrgChartPositionUpdateRequest(UUID.randomUUID(), null, null, 0L, null);
    OrgChartPositionDto response =
        new OrgChartPositionDto(
            id,
            OrgChartPositionType.SQUADRON_LEAD,
            UUID.randomUUID(),
            request.userId(),
            "Lead",
            null,
            null,
            null,
            0,
            1L);
    when(orgChartService.updatePosition(id, request)).thenReturn(response);

    assertSame(response, controller.updatePosition(id, request));
    verify(orgChartService).updatePosition(id, request);
  }

  @Test
  void deletePosition_delegatesIdToService() {
    UUID id = UUID.randomUUID();

    controller.deletePosition(id);

    verify(orgChartService).deletePosition(id);
    verifyNoMoreInteractions(orgChartService);
  }

  @Test
  void vacateCommandLeader_delegatesIdAndVersionToService() {
    UUID id = UUID.randomUUID();
    OrgChartPositionDto response =
        new OrgChartPositionDto(
            id,
            OrgChartPositionType.COMMAND_LEAD,
            UUID.randomUUID(),
            null,
            null,
            null,
            "Alpha",
            null,
            0,
            4L);
    when(orgChartService.vacateCommandLeader(id, 3L)).thenReturn(response);

    assertSame(response, controller.vacateCommandLeader(id, 3L));
    verify(orgChartService).vacateCommandLeader(id, 3L);
  }
}
