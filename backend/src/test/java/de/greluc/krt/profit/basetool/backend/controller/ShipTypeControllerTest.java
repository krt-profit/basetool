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

import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ShipTypeService;
import de.greluc.krt.profit.basetool.backend.catalogue.web.ShipTypeController;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
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
  @Mock private ShipTypeMapper mapper;

  @InjectMocks private ShipTypeController controller;

  @Test
  void getAll_passesIncludeHiddenToService() {
    when(service.getAllShipTypes(any(Pageable.class), eq(true)))
        .thenReturn(new PageImpl<>(List.of()));

    controller.getAllShipTypes(null, null, null, true);

    verify(service).getAllShipTypes(any(Pageable.class), eq(true));
  }

  @Test
  void getAll_wrapsServicePageIntoPageResponse() {
    ShipTypeDto dto = new ShipTypeDto(UUID.randomUUID(), "Cutlass Black", null, null, 46, false);
    when(service.getAllShipTypes(any(Pageable.class), eq(false)))
        .thenReturn(new PageImpl<>(List.of(dto)));

    PageResponse<ShipTypeDto> resp = controller.getAllShipTypes(null, null, null, false);

    assertEquals(1, resp.totalElements());
    assertSame(dto, resp.content().getFirst());
  }

  @Test
  void getById_returnsTheServicesReadModel() {
    UUID id = UUID.randomUUID();
    ShipTypeDto dto = new ShipTypeDto(id, "x", null, null, 1, false);
    when(service.getShipType(id)).thenReturn(dto);

    ShipTypeDto result = controller.getShipType(id);

    assertSame(dto, result);
  }

  @Test
  void updateVisibility_passesHiddenFlagVerbatim() {
    UUID id = UUID.randomUUID();
    ShipType updated = new ShipType();
    ShipTypeDto dto = new ShipTypeDto(id, "x", null, null, 1, true);

    when(service.updateShipTypeVisibility(id, true)).thenReturn(updated);
    when(mapper.toDto(updated)).thenReturn(dto);

    ShipTypeDto result = controller.updateShipTypeVisibility(id, true);

    assertSame(dto, result);
    verify(service).updateShipTypeVisibility(id, true);
  }

  @Test
  void updateVisibility_falsePathForwardsFalse() {
    UUID id = UUID.randomUUID();
    when(service.updateShipTypeVisibility(id, false)).thenReturn(new ShipType());
    when(mapper.toDto(any())).thenReturn(new ShipTypeDto(id, "x", null, null, 1, false));

    controller.updateShipTypeVisibility(id, false);

    verify(service).updateShipTypeVisibility(id, false);
  }
}
