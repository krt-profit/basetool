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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.mapper.SpecialCommandMapper;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.SpecialCommandDto;
import de.greluc.krt.profit.basetool.backend.service.SpecialCommandService;
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
import org.springframework.security.access.AccessDeniedException;

/**
 * Pure-method unit tests for {@link SpecialCommandController}. Mirrors the {@link
 * SquadronControllerTest} contract — verifies the controller's delegation, pagination wrapping, and
 * the includeInactive admin gate. Spring-MVC binding ({@code @PreAuthorize}, JSON) is covered by
 * integration tests elsewhere.
 */
@ExtendWith(MockitoExtension.class)
class SpecialCommandControllerTest {

  @Mock private SpecialCommandService service;
  @Mock private SpecialCommandMapper mapper;

  private SpecialCommandController controller;

  @BeforeEach
  void setUp() {
    controller = new SpecialCommandController(service, mapper, RoleGateFixture.realAuthHelper());
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
      when(service.getAllSpecialCommands(any(Pageable.class), eq(true)))
          .thenReturn(new PageImpl<>(List.of()));
      controller.getAllSpecialCommands(null, null, null, true);
      verify(service).getAllSpecialCommands(any(Pageable.class), eq(true));
    } else {
      assertThrows(
          AccessDeniedException.class,
          () -> controller.getAllSpecialCommands(null, null, null, true));
      verify(service, never()).getAllSpecialCommands(any(Pageable.class), any(Boolean.class));
    }
  }

  @Test
  void getAll_wrapsServicePageIntoPageResponseAndMapsContent() {
    SpecialCommand entity = new SpecialCommand();
    SpecialCommandDto dto =
        new SpecialCommandDto(UUID.randomUUID(), "Alpha", "ALP", "Test", true, false, 1L);
    Page<SpecialCommand> servicePage = new PageImpl<>(List.of(entity));
    when(service.getAllSpecialCommands(any(Pageable.class), eq(false))).thenReturn(servicePage);
    when(mapper.toDto(entity)).thenReturn(dto);

    PageResponse<SpecialCommandDto> resp = controller.getAllSpecialCommands(0, 20, null, false);

    assertEquals(1, resp.totalElements());
    assertEquals(1, resp.content().size());
    assertSame(dto, resp.content().getFirst());
  }

  @Test
  void getAll_includeInactive_withAdminAuth_isForwardedToService() {
    when(service.getAllSpecialCommands(any(Pageable.class), eq(true)))
        .thenReturn(new PageImpl<>(List.of()));

    RoleGateFixture.authenticateAs("ROLE_ADMIN");

    controller.getAllSpecialCommands(null, null, null, true);

    verify(service).getAllSpecialCommands(any(Pageable.class), eq(true));
  }

  @Test
  void getAll_includeInactive_withoutAdminAuth_throwsAccessDenied() {
    RoleGateFixture.authenticateAs("ROLE_KRT_MEMBER");

    assertThrows(
        AccessDeniedException.class,
        () -> controller.getAllSpecialCommands(null, null, null, true));

    verify(service, never()).getAllSpecialCommands(any(Pageable.class), any(Boolean.class));
  }

  @Test
  void getAll_appliesPaginationParameters() {
    when(service.getAllSpecialCommands(any(Pageable.class), eq(false)))
        .thenReturn(new PageImpl<>(List.of()));

    controller.getAllSpecialCommands(3, 75, "name,desc", false);

    ArgumentCaptor<Pageable> pgCap = ArgumentCaptor.forClass(Pageable.class);
    verify(service).getAllSpecialCommands(pgCap.capture(), eq(false));
    assertEquals(3, pgCap.getValue().getPageNumber());
    assertEquals(75, pgCap.getValue().getPageSize());
  }

  @Test
  void getOne_delegatesIdToService() {
    UUID id = UUID.randomUUID();
    SpecialCommand entity = new SpecialCommand();
    SpecialCommandDto dto = new SpecialCommandDto(id, "Alpha", "ALP", null, true, false, 0L);
    when(service.getSpecialCommandById(id)).thenReturn(entity);
    when(mapper.toDto(entity)).thenReturn(dto);

    SpecialCommandDto result = controller.getSpecialCommand(id);

    assertSame(dto, result);
    verify(service).getSpecialCommandById(id);
  }

  @Test
  void create_roundTripsDtoToEntityViaMapperAndBack() {
    SpecialCommandDto request =
        new SpecialCommandDto(null, "Bravo", "BRV", "Test", true, false, null);
    SpecialCommand entity = new SpecialCommand();
    SpecialCommand persisted = new SpecialCommand();
    SpecialCommandDto response =
        new SpecialCommandDto(UUID.randomUUID(), "Bravo", "BRV", "Test", true, false, 1L);

    when(mapper.toEntity(request)).thenReturn(entity);
    when(service.createSpecialCommand(entity)).thenReturn(persisted);
    when(mapper.toDto(persisted)).thenReturn(response);

    SpecialCommandDto result = controller.createSpecialCommand(request);

    assertSame(response, result);
    verify(service).createSpecialCommand(entity);
  }

  @Test
  void update_passesIdAndDtoDirectlyToService() {
    UUID id = UUID.randomUUID();
    SpecialCommandDto request =
        new SpecialCommandDto(id, "Renamed", "REN", "Test", true, false, 4L);
    SpecialCommand persisted = new SpecialCommand();
    SpecialCommandDto response =
        new SpecialCommandDto(id, "Renamed", "REN", "Test", true, false, 5L);

    when(service.updateSpecialCommand(id, request)).thenReturn(persisted);
    when(mapper.toDto(persisted)).thenReturn(response);

    SpecialCommandDto result = controller.updateSpecialCommand(id, request);

    assertSame(response, result);
    verify(service).updateSpecialCommand(id, request);
    verify(mapper, never()).toEntity(any(SpecialCommandDto.class));
  }

  @Test
  void setProfitEligible_delegatesToServiceAndMapsResult() {
    UUID id = UUID.randomUUID();
    SpecialCommand persisted = new SpecialCommand();
    SpecialCommandDto response = new SpecialCommandDto(id, "Alpha", "ALP", "Test", true, true, 2L);
    when(service.setProfitEligible(id, true)).thenReturn(persisted);
    when(mapper.toDto(persisted)).thenReturn(response);

    SpecialCommandDto result =
        controller.setProfitEligible(
            id, new SpecialCommandController.SpecialCommandProfitEligibleToggleRequest(true));

    assertSame(response, result);
    verify(service).setProfitEligible(id, true);
  }

  @Test
  void delete_delegatesIdToService() {
    UUID id = UUID.randomUUID();

    controller.deleteSpecialCommand(id);

    verify(service).deleteSpecialCommand(id);
    verifyNoMoreInteractions(service);
  }

  @Test
  void activate_delegatesIdToService() {
    UUID id = UUID.randomUUID();

    controller.activateSpecialCommand(id);

    verify(service).activateSpecialCommand(id);
    verifyNoMoreInteractions(service);
  }
}
