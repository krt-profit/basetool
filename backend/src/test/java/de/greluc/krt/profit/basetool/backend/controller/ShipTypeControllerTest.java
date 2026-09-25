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

import de.greluc.krt.profit.basetool.backend.mapper.ShipMapper;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipTypeDto;
import de.greluc.krt.profit.basetool.backend.service.ShipTypeService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/** Unit tests for {@link ShipTypeController}: delegation and the {@code hidden} flag update. */
@ExtendWith(MockitoExtension.class)
class ShipTypeControllerTest {

  @Mock private ShipTypeService service;
  @Mock private ShipMapper mapper;

  @InjectMocks private ShipTypeController controller;

  @Test
  void getAll_passesIncludeHiddenToService() {
    when(service.getAllShipTypes(any(Pageable.class), eq(true)))
        .thenReturn(new PageImpl<>(List.of()));

    controller.getAllShipTypes(null, null, null, true);

    verify(service).getAllShipTypes(any(Pageable.class), eq(true));
  }

  @Test
  void getAll_wrapsServicePageIntoPageResponse_usingShipTypeMapper() {
    ShipType entity = new ShipType();
    ShipTypeDto dto = new ShipTypeDto(UUID.randomUUID(), "Cutlass Black", null, null, 46, false);
    when(service.getAllShipTypes(any(Pageable.class), eq(false)))
        .thenReturn(new PageImpl<>(List.of(entity)));
    when(mapper.shipTypeToDto(entity)).thenReturn(dto);

    PageResponse<ShipTypeDto> resp = controller.getAllShipTypes(null, null, null, false);

    assertEquals(1, resp.totalElements());
    assertSame(dto, resp.content().getFirst());
    verify(mapper).shipTypeToDto(entity);
  }

  @Test
  void getById_delegatesAndMaps() {
    UUID id = UUID.randomUUID();
    ShipType entity = new ShipType();
    ShipTypeDto dto = new ShipTypeDto(id, "x", null, null, 1, false);
    when(service.getShipType(id)).thenReturn(entity);
    when(mapper.shipTypeToDto(entity)).thenReturn(dto);

    ShipTypeDto result = controller.getShipType(id);

    assertSame(dto, result);
  }

  @Test
  void updateVisibility_passesHiddenFlagVerbatim() {
    UUID id = UUID.randomUUID();
    ShipType updated = new ShipType();
    ShipTypeDto dto = new ShipTypeDto(id, "x", null, null, 1, true);

    when(service.updateShipTypeVisibility(id, true)).thenReturn(updated);
    when(mapper.shipTypeToDto(updated)).thenReturn(dto);

    ShipTypeDto result = controller.updateShipTypeVisibility(id, true);

    assertSame(dto, result);
    verify(service).updateShipTypeVisibility(id, true);
  }

  @Test
  void updateVisibility_falsePathForwardsFalse() {
    UUID id = UUID.randomUUID();
    when(service.updateShipTypeVisibility(id, false)).thenReturn(new ShipType());
    when(mapper.shipTypeToDto(any())).thenReturn(new ShipTypeDto(id, "x", null, null, 1, false));

    controller.updateShipTypeVisibility(id, false);

    verify(service).updateShipTypeVisibility(id, false);
  }
}
