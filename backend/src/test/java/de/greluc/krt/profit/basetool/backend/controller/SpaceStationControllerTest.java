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
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.catalogue.internal.SpaceStationDto;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.mapper.SpaceStationMapper;
import de.greluc.krt.profit.basetool.backend.model.SpaceStation;
import de.greluc.krt.profit.basetool.backend.service.SpaceStationService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/** Mockito unit tests for {@link SpaceStationController}. */
@ExtendWith(MockitoExtension.class)
class SpaceStationControllerTest {

  @Mock private SpaceStationService service;
  @Mock private SpaceStationMapper mapper;

  @InjectMocks private SpaceStationController controller;

  @Test
  void getAllSpaceStations_wrapsServicePageIntoPageResponse() {
    SpaceStation entity = new SpaceStation();
    SpaceStationDto dto =
        new SpaceStationDto(UUID.randomUUID(), "Port Olisar", "Stanton", "Crusader", true, false);
    when(service.getAllSpaceStations(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(entity)));
    when(mapper.toDto(entity)).thenReturn(dto);

    PageResponse<SpaceStationDto> resp = controller.getAllSpaceStations(null, null, null);

    assertEquals(1, resp.totalElements());
    assertSame(dto, resp.content().getFirst());
  }

  @Test
  void getSpaceStation_delegatesAndMaps() {
    UUID id = UUID.randomUUID();
    SpaceStation entity = new SpaceStation();
    SpaceStationDto dto =
        new SpaceStationDto(id, "Port Olisar", "Stanton", "Crusader", true, false);
    when(service.getSpaceStation(id)).thenReturn(entity);
    when(mapper.toDto(entity)).thenReturn(dto);

    SpaceStationDto result = controller.getSpaceStation(id);

    assertSame(dto, result);
  }

  @Test
  void setLoadingDockOverride_forwardsValueAndReturnsDto() {
    UUID id = UUID.randomUUID();
    SpaceStation updated = new SpaceStation();
    SpaceStationDto response =
        new SpaceStationDto(id, "Port Olisar", "Stanton", "Crusader", true, true);
    when(service.setLoadingDockOverride(id, true)).thenReturn(updated);
    when(mapper.toDto(updated)).thenReturn(response);

    SpaceStationDto result = controller.setLoadingDockOverride(id, true);

    assertSame(response, result);
    verify(service).setLoadingDockOverride(id, true);
  }

  @Test
  void clearLoadingDockOverride_callsServiceAndReturnsDto() {
    UUID id = UUID.randomUUID();
    SpaceStation updated = new SpaceStation();
    SpaceStationDto response =
        new SpaceStationDto(id, "Port Olisar", "Stanton", "Crusader", true, false);
    when(service.clearLoadingDockOverride(id)).thenReturn(updated);
    when(mapper.toDto(updated)).thenReturn(response);

    SpaceStationDto result = controller.clearLoadingDockOverride(id);

    assertSame(response, result);
    verify(service).clearLoadingDockOverride(id);
  }
}
