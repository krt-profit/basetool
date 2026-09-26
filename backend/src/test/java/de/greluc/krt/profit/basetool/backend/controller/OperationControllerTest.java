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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.mapper.OperationMapper;
import de.greluc.krt.profit.basetool.backend.model.Operation;
import de.greluc.krt.profit.basetool.backend.model.OperationStatus;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationUpdateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.service.OperationFinanceService;
import de.greluc.krt.profit.basetool.backend.service.OperationPayoutService;
import de.greluc.krt.profit.basetool.backend.service.OperationService;
import java.time.Instant;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class OperationControllerTest {

  @Mock private OperationService operationService;

  @Mock private OperationPayoutService operationPayoutService;

  @Mock private OperationMapper operationMapper;

  @Mock private OperationFinanceService operationFinanceService;

  private OperationController operationController;

  @BeforeEach
  void setUp() {
    operationController =
        new OperationController(
            operationService,
            operationPayoutService,
            operationMapper,
            operationFinanceService,
            RoleGateFixture.realAuthHelper());
  }

  @AfterEach
  void clearSecurityContext() {
    RoleGateFixture.clear();
  }

  static java.util.stream.Stream<String> callers() {
    return RoleGateFixture.callers();
  }

  /**
   * BE-SIMP-07: the state-machine override flag moved from a raw {@code "ROLE_ADMIN"} scan of the
   * injected {@code Authentication} to {@code AuthHelperService.isAdmin()}. Every caller shape must
   * still hand the service exactly the flag the raw scan produced — only an admin bypasses the
   * status transition rules.
   */
  @ParameterizedTest
  @MethodSource("callers")
  void updateOperation_overridesTheStateMachineExactlyForAnAdmin(String caller) {
    boolean expected = RoleGateFixture.rawCheckAccepted(caller, "ROLE_ADMIN");
    RoleGateFixture.authenticateAs(caller);
    UUID id = UUID.randomUUID();
    OperationUpdateDto update = new OperationUpdateDto("Op", null, OperationStatus.ACTIVE, 1L);
    Operation updated = new Operation();
    when(operationService.updateOperation(id, update, expected)).thenReturn(updated);

    operationController.updateOperation(id, update);

    verify(operationService).updateOperation(id, update, expected);
    verify(operationMapper).toDto(updated);
  }

  @Test
  void shouldCreateOperation() {
    OperationCreateDto createDto =
        new OperationCreateDto("Test", "Desc", OperationStatus.PLANNED, null);
    Operation operation = new Operation();
    OperationDto operationDto =
        new OperationDto(
            UUID.randomUUID(), "Test", "Desc", OperationStatus.PLANNED, null, 0L, null, null, null);

    when(operationMapper.toEntity(createDto)).thenReturn(operation);
    when(operationService.createOperation(operation, null)).thenReturn(operation);
    when(operationMapper.toDto(operation)).thenReturn(operationDto);

    OperationDto result = operationController.createOperation(createDto);

    assertNotNull(result);
    assertEquals("Test", result.name());
    verify(operationService, times(1)).createOperation(operation, null);
  }

  @Test
  void getAllOperations_defaultParams_sortsByCreatedAtDesc_withIdAsTiebreaker() {
    when(operationService.getAllOperations(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    operationController.getAllOperations(0, 10, "createdAt,desc");

    ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
    verify(operationService).getAllOperations(captor.capture());
    Pageable pageable = captor.getValue();
    assertEquals(0, pageable.getPageNumber());
    assertEquals(10, pageable.getPageSize());

    Sort sort = pageable.getSort();
    Sort.Order primary = sort.getOrderFor("createdAt");
    assertNotNull(primary, "createdAt must be in the sort");
    assertEquals(Sort.Direction.DESC, primary.getDirection());
    assertNotNull(
        sort.getOrderFor("id"),
        "PaginationUtil must add `id` as a secondary sort for page stability");
  }

  @Test
  void getAllOperations_acceptsWhitelistedSortFields() {
    when(operationService.getAllOperations(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    for (String field : List.of("id", "name", "status", "description", "createdAt", "updatedAt")) {
      operationController.getAllOperations(0, 10, field + ",asc");
    }

    verify(operationService, times(6)).getAllOperations(any(Pageable.class));
  }

  @Test
  void getAllOperations_rejectsUnknownSortField_with400() {
    assertThrows(
        IllegalArgumentException.class,
        () -> operationController.getAllOperations(0, 10, "password,asc"));

    verify(operationService, never()).getAllOperations(any(Pageable.class));
  }

  @Test
  void getAllOperations_rejectsArbitraryJpaPath_with400() {
    assertThrows(
        IllegalArgumentException.class,
        () -> operationController.getAllOperations(0, 10, "missions.id,asc"));
  }

  @Test
  void getAllOperations_wrapsServicePageIntoPageResponse() {
    Operation entity = new Operation();
    OperationDto dto =
        new OperationDto(
            UUID.randomUUID(), "Op", "d", OperationStatus.PLANNED, null, 0L, null, null, null);
    when(operationService.getAllOperations(any(Pageable.class)))
        .thenAnswer(invocation -> new PageImpl<>(List.of(entity), invocation.getArgument(0), 1));
    when(operationMapper.toDto(entity)).thenReturn(dto);

    PageResponse<OperationDto> resp = operationController.getAllOperations(0, 10, "createdAt,desc");

    assertEquals(1, resp.totalElements());
    assertEquals(dto, resp.content().getFirst());
    assertTrue(
        resp.sort().contains("createdAt,desc"), "the echoed sort must reflect the active ordering");
  }

  @Test
  void getOperationById_stampsPayoutPreliminaryFromService_true() {
    UUID id = UUID.randomUUID();
    Operation entity = new Operation();
    OperationDto baseDto =
        new OperationDto(id, "Op", "d", OperationStatus.PLANNED, null, 0L, null, null, null);
    when(operationService.getOperationById(id)).thenReturn(entity);
    when(operationMapper.toDto(entity)).thenReturn(baseDto);
    when(operationService.hasUnfinishedMissions(id)).thenReturn(true);

    OperationDto result = operationController.getOperationById(id);

    assertEquals(Boolean.TRUE, result.payoutPreliminary());
    assertEquals(id, result.id());
    verify(operationService, times(1)).hasUnfinishedMissions(id);
  }

  @Test
  void getOperationById_stampsPayoutPreliminaryFromService_false() {
    UUID id = UUID.randomUUID();
    Operation entity = new Operation();
    OperationDto baseDto =
        new OperationDto(id, "Op", "d", OperationStatus.PLANNED, null, 0L, null, null, null);
    when(operationService.getOperationById(id)).thenReturn(entity);
    when(operationMapper.toDto(entity)).thenReturn(baseDto);
    when(operationService.hasUnfinishedMissions(id)).thenReturn(false);

    OperationDto result = operationController.getOperationById(id);

    assertEquals(Boolean.FALSE, result.payoutPreliminary());
  }

  @Test
  void searchOperations_forwardsFiltersToServiceAndWrapsPageResponse() {
    List<String> statuses = List.of("PLANNED", "ACTIVE");
    Operation entity = new Operation();
    OperationDto dto =
        new OperationDto(
            UUID.randomUUID(), "Op", "d", OperationStatus.PLANNED, null, 0L, null, null, null);

    when(operationService.searchOperations(
            eq("alpha"), any(), any(), eq(statuses), any(Pageable.class)))
        .thenAnswer(invocation -> new PageImpl<>(List.of(entity), invocation.getArgument(4), 1));
    when(operationMapper.toDto(entity)).thenReturn(dto);

    PageResponse<OperationDto> resp =
        operationController.searchOperations(
            "alpha", null, null, statuses, 0, 10, "createdAt,desc");

    assertEquals(1, resp.totalElements());
    assertEquals(dto, resp.content().getFirst());
    verify(operationService, times(1))
        .searchOperations(eq("alpha"), any(), any(), eq(statuses), any(Pageable.class));
  }

  @Test
  void searchOperations_rejectsUnknownSortField_with400() {
    assertThrows(
        IllegalArgumentException.class,
        () -> operationController.searchOperations(null, null, null, null, 0, 10, "password,asc"));

    verify(operationService, never())
        .searchOperations(any(), any(), any(), any(), any(Pageable.class));
  }

  @Test
  void searchOperations_appendsIdAsStableTiebreaker() {
    when(operationService.searchOperations(any(), any(), any(), any(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    operationController.searchOperations(null, null, null, null, 0, 10, "createdAt,desc");

    ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
    verify(operationService).searchOperations(any(), any(), any(), any(), captor.capture());
    Sort sort = captor.getValue().getSort();
    assertNotNull(sort.getOrderFor("createdAt"));
    assertNotNull(
        sort.getOrderFor("id"), "PaginationUtil must append `id` as a stable secondary sort");
  }

  @Test
  void searchOperations_forwardsTimeRangeBoundsToService() {
    Instant start = Instant.parse("2026-06-01T00:00:00Z");
    Instant end = Instant.parse("2026-06-30T23:59:00Z");
    when(operationService.searchOperations(any(), eq(start), eq(end), any(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    operationController.searchOperations(null, start, end, null, 0, 10, "createdAt,desc");

    verify(operationService, times(1))
        .searchOperations(any(), eq(start), eq(end), any(), any(Pageable.class));
  }
}
