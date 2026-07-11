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
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.service.OperationFinanceService;
import de.greluc.krt.profit.basetool.backend.service.OperationPayoutService;
import de.greluc.krt.profit.basetool.backend.service.OperationService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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

  @InjectMocks private OperationController operationController;

  @Test
  void shouldCreateOperation() {
    // Given
    OperationCreateDto createDto =
        new OperationCreateDto("Test", "Desc", OperationStatus.PLANNED, null);
    Operation operation = new Operation();
    OperationDto operationDto =
        new OperationDto(
            UUID.randomUUID(), "Test", "Desc", OperationStatus.PLANNED, null, 0L, null, null, null);

    when(operationMapper.toEntity(createDto)).thenReturn(operation);
    when(operationService.createOperation(operation, null)).thenReturn(operation);
    when(operationMapper.toDto(operation)).thenReturn(operationDto);

    // When
    OperationDto result = operationController.createOperation(createDto);

    // Then
    assertNotNull(result);
    assertEquals("Test", result.name());
    verify(operationService, times(1)).createOperation(operation, null);
  }

  // --- Sort whitelisting ---------------------------------------------------
  // CLAUDE.md hard rule: list endpoints must NOT pass user-supplied sort
  // field names straight into Sort.by(...). The controller funnels through
  // PaginationUtil + ALLOWED_SORT and any unknown field must surface as a
  // 400 (IllegalArgumentException -> GlobalExceptionHandler).

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
    // PaginationUtil appends id as a stable tiebreaker because id is in
    // ALLOWED_SORT. Without this, two operations with the same createdAt
    // could swap order between pages.
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
    // "password" is a typical hostile probe — it is not a column on
    // Operation, and accepting it would either crash with a 500 (Spring
    // JPA can't resolve the property) or, worse, leak schema details.
    // PaginationUtil short-circuits to IllegalArgumentException, which
    // GlobalExceptionHandler translates to a problem+json 400.
    assertThrows(
        IllegalArgumentException.class,
        () -> operationController.getAllOperations(0, 10, "password,asc"));

    verify(operationService, never()).getAllOperations(any(Pageable.class));
  }

  @Test
  void getAllOperations_rejectsArbitraryJpaPath_with400() {
    // Regression guard: even a real JPA path like `missions.id` (which
    // would otherwise be silently accepted by Spring Data JPA and trigger
    // a join) must be rejected because the whitelist only contains scalar
    // fields on Operation itself.
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
    // Echo the incoming Pageable back through the PageImpl so the
    // Sort survives the .map() in the controller — otherwise getSort()
    // would default to Sort.unsorted() and the assertion below loses
    // meaning.
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

  // --- searchOperations ----------------------------------------------------

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
    // Defence-in-depth: the search endpoint reuses the same ALLOWED_SORT whitelist as
    // getAllOperations, so an attacker probing typical column names must be short-circuited
    // before the service is ever called.
    assertThrows(
        IllegalArgumentException.class,
        () -> operationController.searchOperations(null, null, null, null, 0, 10, "password,asc"));

    verify(operationService, never())
        .searchOperations(any(), any(), any(), any(), any(Pageable.class));
  }

  @Test
  void searchOperations_appendsIdAsStableTiebreaker() {
    // Two operations created at the same Instant would otherwise swap order between pages —
    // PaginationUtil must append `id` as a secondary sort because it's in the whitelist.
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
    // The start/end query params bind as ISO-8601 instants and must reach the service untouched —
    // they drive the operation's derived mission-span filter (earliest planned start / latest
    // planned end).
    Instant start = Instant.parse("2026-06-01T00:00:00Z");
    Instant end = Instant.parse("2026-06-30T23:59:00Z");
    when(operationService.searchOperations(any(), eq(start), eq(end), any(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    operationController.searchOperations(null, start, end, null, 0, 10, "createdAt,desc");

    verify(operationService, times(1))
        .searchOperations(any(), eq(start), eq(end), any(), any(Pageable.class));
  }
}
