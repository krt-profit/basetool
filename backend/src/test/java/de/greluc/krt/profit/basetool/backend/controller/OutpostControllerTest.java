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

import de.greluc.krt.profit.basetool.backend.catalogue.api.OutpostDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.Outpost;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.OutpostMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.OutpostService;
import de.greluc.krt.profit.basetool.backend.catalogue.web.OutpostController;
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

/** Mockito unit tests for {@link OutpostController}. */
@ExtendWith(MockitoExtension.class)
class OutpostControllerTest {

  @Mock private OutpostService service;
  @Mock private OutpostMapper mapper;

  @InjectMocks private OutpostController controller;

  @Test
  void getAllOutposts_wrapsServicePageIntoPageResponse() {
    OutpostDto dto =
        new OutpostDto(UUID.randomUUID(), "HDMS-Anderson", "Stanton", "Hurston", true, false);
    when(service.getAllOutposts(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(dto)));

    PageResponse<OutpostDto> resp = controller.getAllOutposts(null, null, null);

    assertEquals(1, resp.totalElements());
    assertSame(dto, resp.content().getFirst());
  }

  @Test
  void getOutpost_returnsTheServicesReadModel() {
    UUID id = UUID.randomUUID();
    OutpostDto dto = new OutpostDto(id, "HDMS-Anderson", "Stanton", "Hurston", true, false);
    when(service.getOutpost(id)).thenReturn(dto);

    OutpostDto result = controller.getOutpost(id);

    assertSame(dto, result);
  }

  @Test
  void setLoadingDockOverride_forwardsValueAndReturnsDto() {
    UUID id = UUID.randomUUID();
    Outpost updated = new Outpost();
    OutpostDto response = new OutpostDto(id, "HDMS-Anderson", "Stanton", "Hurston", true, true);
    when(service.setLoadingDockOverride(id, true)).thenReturn(updated);
    when(mapper.toDto(updated)).thenReturn(response);

    OutpostDto result = controller.setLoadingDockOverride(id, true);

    assertSame(response, result);
    verify(service).setLoadingDockOverride(id, true);
  }

  @Test
  void clearLoadingDockOverride_callsServiceAndReturnsDto() {
    UUID id = UUID.randomUUID();
    Outpost updated = new Outpost();
    OutpostDto response = new OutpostDto(id, "HDMS-Anderson", "Stanton", "Hurston", true, false);
    when(service.clearLoadingDockOverride(id)).thenReturn(updated);
    when(mapper.toDto(updated)).thenReturn(response);

    OutpostDto result = controller.clearLoadingDockOverride(id);

    assertSame(response, result);
    verify(service).clearLoadingDockOverride(id);
  }
}
