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

import de.greluc.krt.profit.basetool.backend.mapper.TerminalMapper;
import de.greluc.krt.profit.basetool.backend.model.Terminal;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.TerminalDto;
import de.greluc.krt.profit.basetool.backend.service.TerminalService;
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
 * Unit tests for {@link TerminalController}: the PUT endpoint applies only the {@code hidden} flag
 * of the submitted {@link TerminalDto}.
 */
@ExtendWith(MockitoExtension.class)
class TerminalControllerTest {

  @Mock private TerminalService service;
  @Mock private TerminalMapper mapper;

  @InjectMocks private TerminalController controller;

  @Test
  void getAllTerminals_wrapsServicePageIntoPageResponse() {
    Terminal entity = new Terminal();
    TerminalDto dto =
        new TerminalDto(
            UUID.randomUUID(),
            "TDD A18",
            null,
            "Stanton",
            "ArcCorp",
            "Area18",
            null,
            null,
            null,
            false,
            false,
            null,
            null,
            null,
            false);
    when(service.getAllTerminals(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(entity)));
    when(mapper.toDto(entity)).thenReturn(dto);

    PageResponse<TerminalDto> resp = controller.getAllTerminals(null, null, null);

    assertEquals(1, resp.totalElements());
    assertSame(dto, resp.content().getFirst());
  }

  @Test
  void getTerminal_delegatesAndMaps() {
    UUID id = UUID.randomUUID();
    Terminal entity = new Terminal();
    TerminalDto dto =
        new TerminalDto(
            id, "x", null, null, null, null, null, null, null, false, false, null, null, null,
            false);
    when(service.getTerminal(id)).thenReturn(entity);
    when(mapper.toDto(entity)).thenReturn(dto);

    TerminalDto result = controller.getTerminal(id);

    assertSame(dto, result);
  }

  @Test
  void updateTerminal_onlyPropagatesHiddenFlag_notFullDto() {
    UUID id = UUID.randomUUID();
    TerminalDto request =
        new TerminalDto(
            id,
            "Renamed",
            "Override",
            "FakeSystem",
            "FakePlanet",
            "FakeCity",
            "FakeStation",
            true,
            true,
            true,
            true,
            null,
            null,
            null,
            true);
    Terminal updated = new Terminal();
    TerminalDto response =
        new TerminalDto(
            id,
            "OriginalName",
            null,
            "Stanton",
            "ArcCorp",
            "Area18",
            null,
            null,
            null,
            false,
            false,
            null,
            null,
            null,
            true);

    when(service.updateTerminalVisibility(id, true)).thenReturn(updated);
    when(mapper.toDto(updated)).thenReturn(response);

    TerminalDto result = controller.updateTerminal(id, request);

    assertSame(response, result);
    verify(service).updateTerminalVisibility(id, true);
    verify(service, never()).getTerminal(any());
  }

  @Test
  void updateTerminal_passesHiddenFalseVerbatim() {
    UUID id = UUID.randomUUID();
    TerminalDto request =
        new TerminalDto(
            id, "x", null, null, null, null, null, null, null, false, false, null, null, null,
            false);
    when(service.updateTerminalVisibility(id, false)).thenReturn(new Terminal());
    when(mapper.toDto(any())).thenReturn(request);

    controller.updateTerminal(id, request);

    verify(service).updateTerminalVisibility(id, false);
  }

  @Test
  void setLoadingDockOverride_forwardsValueAndReturnsDto() {
    UUID id = UUID.randomUUID();
    Terminal updated = new Terminal();
    TerminalDto response =
        new TerminalDto(
            id, "x", null, null, null, null, null, true, null, true, false, null, null, null,
            false);
    when(service.setLoadingDockOverride(id, true)).thenReturn(updated);
    when(mapper.toDto(updated)).thenReturn(response);

    TerminalDto result = controller.setLoadingDockOverride(id, true);

    assertSame(response, result);
    verify(service).setLoadingDockOverride(id, true);
  }

  @Test
  void clearLoadingDockOverride_callsServiceAndReturnsDto() {
    UUID id = UUID.randomUUID();
    Terminal updated = new Terminal();
    TerminalDto response =
        new TerminalDto(
            id, "x", null, null, null, null, null, false, null, false, false, null, null, null,
            false);
    when(service.clearLoadingDockOverride(id)).thenReturn(updated);
    when(mapper.toDto(updated)).thenReturn(response);

    TerminalDto result = controller.clearLoadingDockOverride(id);

    assertSame(response, result);
    verify(service).clearLoadingDockOverride(id);
  }

  @Test
  void setAutoLoadOverride_forwardsValueAndReturnsDto() {
    UUID id = UUID.randomUUID();
    Terminal updated = new Terminal();
    TerminalDto response =
        new TerminalDto(
            id, "x", null, null, null, null, null, null, true, false, true, null, null, null,
            false);
    when(service.setAutoLoadOverride(id, true)).thenReturn(updated);
    when(mapper.toDto(updated)).thenReturn(response);

    TerminalDto result = controller.setAutoLoadOverride(id, true);

    assertSame(response, result);
    verify(service).setAutoLoadOverride(id, true);
  }

  @Test
  void clearAutoLoadOverride_callsServiceAndReturnsDto() {
    UUID id = UUID.randomUUID();
    Terminal updated = new Terminal();
    TerminalDto response =
        new TerminalDto(
            id, "x", null, null, null, null, null, null, false, false, false, null, null, null,
            false);
    when(service.clearAutoLoadOverride(id)).thenReturn(updated);
    when(mapper.toDto(updated)).thenReturn(response);

    TerminalDto result = controller.clearAutoLoadOverride(id);

    assertSame(response, result);
    verify(service).clearAutoLoadOverride(id);
  }
}
