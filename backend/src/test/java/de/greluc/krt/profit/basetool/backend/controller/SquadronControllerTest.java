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

import de.greluc.krt.profit.basetool.backend.mapper.SquadronMapper;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronDto;
import de.greluc.krt.profit.basetool.backend.service.SquadronService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Pure-method unit tests for {@link SquadronController}. The Spring-MVC binding (`@PreAuthorize`,
 * JSON) is covered by integration tests; here we verify the controller's delegation contract and
 * pagination wrapping.
 */
@ExtendWith(MockitoExtension.class)
class SquadronControllerTest {

  @Mock private SquadronService service;
  @Mock private SquadronMapper mapper;

  private SquadronController controller;

  @BeforeEach
  void setUp() {
    controller = new SquadronController(service, mapper, RoleGateFixture.realAuthHelper());
  }

  @AfterEach
  void clearSecurityContext() {
    RoleGateFixture.clear();
  }

  static java.util.stream.Stream<String> callers() {
    return RoleGateFixture.callers();
  }

  /**
   * BE-SIMP-07: the {@code includeInactive} gate moved from a raw {@code "ROLE_ADMIN"} scan of the
   * injected {@code Authentication} to {@code AuthHelperService.isAdmin()}. Every caller shape must
   * still get exactly the answer the raw scan gave: an admin reaches the service, everyone else is
   * refused before it.
   */
  @ParameterizedTest
  @MethodSource("callers")
  void getAll_includeInactive_isAdminOnlyForEveryCallerShape(String caller) {
    boolean admitted = RoleGateFixture.rawCheckAccepted(caller, "ROLE_ADMIN");
    RoleGateFixture.authenticateAs(caller);
    if (admitted) {
      when(service.getAllSquadrons(any(Pageable.class), eq(true)))
          .thenReturn(new PageImpl<>(List.of()));
      controller.getAllSquadrons(null, null, null, true);
      verify(service).getAllSquadrons(any(Pageable.class), eq(true));
    } else {
      assertThrows(
          org.springframework.security.access.AccessDeniedException.class,
          () -> controller.getAllSquadrons(null, null, null, true));
      verify(service, never()).getAllSquadrons(any(Pageable.class), any(Boolean.class));
    }
  }

  @Test
  void getAll_wrapsServicePageIntoPageResponseAndMapsContent() {
    Squadron entity = new Squadron();
    SquadronDto dto =
        new SquadronDto(UUID.randomUUID(), "Alpha", "ALP", "Test", true, true, false, 1L);
    Page<Squadron> servicePage = new PageImpl<>(List.of(entity));
    when(service.getAllSquadrons(any(Pageable.class), eq(false))).thenReturn(servicePage);
    when(mapper.toDto(entity)).thenReturn(dto);

    PageResponse<SquadronDto> resp = controller.getAllSquadrons(0, 20, null, false);

    assertEquals(1, resp.totalElements());
    assertEquals(1, resp.content().size());
    assertSame(dto, resp.content().getFirst());
  }

  @Test
  void getAll_includeInactive_isForwardedToService() {
    when(service.getAllSquadrons(any(Pageable.class), eq(true)))
        .thenReturn(new PageImpl<>(List.of()));

    RoleGateFixture.authenticateAs("ROLE_ADMIN");
    controller.getAllSquadrons(null, null, null, true);

    verify(service).getAllSquadrons(any(Pageable.class), eq(true));
  }

  @Test
  void getAll_appliesPaginationParameters() {
    when(service.getAllSquadrons(any(Pageable.class), eq(false)))
        .thenReturn(new PageImpl<>(List.of()));

    controller.getAllSquadrons(3, 75, "name,desc", false);

    ArgumentCaptor<Pageable> pgCap = ArgumentCaptor.forClass(Pageable.class);
    verify(service).getAllSquadrons(pgCap.capture(), eq(false));
    assertEquals(3, pgCap.getValue().getPageNumber());
    assertEquals(75, pgCap.getValue().getPageSize());
  }

  @Test
  void create_roundTripsDtoToEntityViaMapperAndBack() {
    SquadronDto request = new SquadronDto(null, "Bravo", "BRV", "Test", true, true, false, null);
    Squadron entity = new Squadron();
    Squadron persisted = new Squadron();
    SquadronDto response =
        new SquadronDto(UUID.randomUUID(), "Bravo", "BRV", "Test", true, true, false, 1L);

    when(mapper.toEntity(request)).thenReturn(entity);
    when(service.createSquadron(entity)).thenReturn(persisted);
    when(mapper.toDto(persisted)).thenReturn(response);

    SquadronDto result = controller.createSquadron(request);

    assertSame(response, result);
    verify(service).createSquadron(entity);
  }

  @Test
  void update_passesIdAndDtoDirectlyToService() {
    UUID id = UUID.randomUUID();
    SquadronDto request = new SquadronDto(id, "Renamed", "REN", "Test", true, true, false, 4L);
    Squadron persisted = new Squadron();
    SquadronDto response = new SquadronDto(id, "Renamed", "REN", "Test", true, true, false, 5L);

    when(service.updateSquadron(id, request)).thenReturn(persisted);
    when(mapper.toDto(persisted)).thenReturn(response);

    SquadronDto result = controller.updateSquadron(id, request);

    assertSame(response, result);
    verify(service).updateSquadron(id, request);
    verify(mapper, never()).toEntity(any(SquadronDto.class));
  }

  @Test
  void setProfitEligible_delegatesToServiceAndMapsResult() {
    UUID id = UUID.randomUUID();
    Squadron persisted = new Squadron();
    SquadronDto response = new SquadronDto(id, "Alpha", "ALP", "Test", true, true, true, 2L);
    when(service.setProfitEligible(id, true)).thenReturn(persisted);
    when(mapper.toDto(persisted)).thenReturn(response);

    SquadronDto result =
        controller.setProfitEligible(
            id, new SquadronController.SquadronProfitEligibleToggleRequest(true));

    assertSame(response, result);
    verify(service).setProfitEligible(id, true);
  }

  @Test
  void delete_delegatesIdToService() {
    UUID id = UUID.randomUUID();

    controller.deleteSquadron(id);

    verify(service).deleteSquadron(id);
    verifyNoMoreInteractions(service);
  }

  @Test
  void activate_delegatesIdToService() {
    UUID id = UUID.randomUUID();

    controller.activateSquadron(id);

    verify(service).activateSquadron(id);
    verifyNoMoreInteractions(service);
  }
}
