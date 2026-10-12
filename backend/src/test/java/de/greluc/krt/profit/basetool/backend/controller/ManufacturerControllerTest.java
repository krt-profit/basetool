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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ManufacturerDto;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.mapper.ManufacturerMapper;
import de.greluc.krt.profit.basetool.backend.service.ManufacturerService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Pure-method unit tests for {@link ManufacturerController}. UEX-imported manufacturers are
 * read-only from the regular API surface; only the `hidden` flag is mutable via the admin
 * visibility endpoint.
 */
@ExtendWith(MockitoExtension.class)
class ManufacturerControllerTest {

  @Mock private ManufacturerService service;
  @Mock private ManufacturerMapper mapper;

  @InjectMocks private ManufacturerController controller;

  @Test
  void getAllManufacturers_passesIncludeHiddenToService() {
    when(service.getAllManufacturers(any(Pageable.class), eq(true)))
        .thenReturn(new PageImpl<>(List.of()));

    controller.getAllManufacturers(null, null, null, true);

    verify(service).getAllManufacturers(any(Pageable.class), eq(true));
  }

  @Test
  void getAllManufacturers_wrapsServicePageIntoPageResponse() {
    ManufacturerDto dto =
        new ManufacturerDto(UUID.randomUUID(), "Drake", "DRAK", null, null, null, false);
    when(service.getAllManufacturers(any(Pageable.class), eq(false)))
        .thenReturn(new PageImpl<>(List.of(dto)));

    PageResponse<ManufacturerDto> resp = controller.getAllManufacturers(0, 50, null, false);

    assertEquals(1, resp.totalElements());
    assertSame(dto, resp.content().getFirst());
  }

  @Test
  void getManufacturer_returnsTheServicesReadModel() {
    UUID id = UUID.randomUUID();
    ManufacturerDto dto = new ManufacturerDto(id, "Anvil", "ANVL", null, null, null, false);
    when(service.getManufacturer(id)).thenReturn(dto);

    ManufacturerDto result = controller.getManufacturer(id);

    assertSame(dto, result);
  }

  @Test
  void updateVisibility_passesHiddenFlagVerbatim() {
    UUID id = UUID.randomUUID();
    Manufacturer m = new Manufacturer();
    ManufacturerDto dto = new ManufacturerDto(id, "x", "x", null, null, null, true);
    when(service.updateManufacturerVisibility(id, true)).thenReturn(m);
    when(mapper.toDto(m)).thenReturn(dto);

    ManufacturerDto result = controller.updateManufacturerVisibility(id, true);

    assertSame(dto, result);
    verify(service).updateManufacturerVisibility(id, true);
  }

  @Test
  void updateVisibility_falsePathForwardsFalse() {
    UUID id = UUID.randomUUID();
    when(service.updateManufacturerVisibility(id, false)).thenReturn(new Manufacturer());
    when(mapper.toDto(any()))
        .thenReturn(new ManufacturerDto(id, "x", "x", null, null, null, false));

    controller.updateManufacturerVisibility(id, false);

    verify(service).updateManufacturerVisibility(id, false);
  }
}
