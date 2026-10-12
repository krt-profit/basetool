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

import de.greluc.krt.profit.basetool.backend.catalogue.api.PoiDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.Poi;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.PoiMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.PoiService;
import de.greluc.krt.profit.basetool.backend.catalogue.web.PoiController;
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

/** Mockito unit tests for {@link PoiController}. */
@ExtendWith(MockitoExtension.class)
class PoiControllerTest {

  @Mock private PoiService service;
  @Mock private PoiMapper mapper;

  @InjectMocks private PoiController controller;

  @Test
  void getAllPois_wrapsServicePageIntoPageResponse() {
    PoiDto dto = new PoiDto(UUID.randomUUID(), "Grim HEX", "Stanton", "Crusader", true, false);
    when(service.getAllPois(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(dto)));

    PageResponse<PoiDto> resp = controller.getAllPois(null, null, null);

    assertEquals(1, resp.totalElements());
    assertSame(dto, resp.content().getFirst());
  }

  @Test
  void getPoi_returnsTheServicesReadModel() {
    UUID id = UUID.randomUUID();
    PoiDto dto = new PoiDto(id, "Grim HEX", "Stanton", "Crusader", true, false);
    when(service.getPoi(id)).thenReturn(dto);

    PoiDto result = controller.getPoi(id);

    assertSame(dto, result);
  }

  @Test
  void setLoadingDockOverride_forwardsValueAndReturnsDto() {
    UUID id = UUID.randomUUID();
    Poi updated = new Poi();
    PoiDto response = new PoiDto(id, "Grim HEX", "Stanton", "Crusader", true, true);
    when(service.setLoadingDockOverride(id, true)).thenReturn(updated);
    when(mapper.toDto(updated)).thenReturn(response);

    PoiDto result = controller.setLoadingDockOverride(id, true);

    assertSame(response, result);
    verify(service).setLoadingDockOverride(id, true);
  }

  @Test
  void clearLoadingDockOverride_callsServiceAndReturnsDto() {
    UUID id = UUID.randomUUID();
    Poi updated = new Poi();
    PoiDto response = new PoiDto(id, "Grim HEX", "Stanton", "Crusader", true, false);
    when(service.clearLoadingDockOverride(id)).thenReturn(updated);
    when(mapper.toDto(updated)).thenReturn(response);

    PoiDto result = controller.clearLoadingDockOverride(id);

    assertSame(response, result);
    verify(service).clearLoadingDockOverride(id);
  }
}
