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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.Operation;
import de.greluc.krt.profit.basetool.backend.model.OperationStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationUpdateDto;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OperationRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class OperationServiceTest {

  @Mock private OperationRepository operationRepository;
  @Mock private MissionRepository missionRepository;
  @Mock private UserService userService;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private AuthHelperService authHelperService;

  @Mock private AuditService auditService;

  @InjectMocks private OperationService operationService;

  @Test
  void shouldCreateOperation() {
    // Given
    Operation operation = new Operation();
    operation.setName("Test Op");
    operation.setStatus(OperationStatus.PLANNED);

    when(operationRepository.save(any(Operation.class))).thenReturn(operation);
    // No caller resolved → service falls back to currentOrgUnit(), which we leave empty here
    // because the test doesn't care about the stamp value, only that the save runs.
    when(userService.getCurrentUser()).thenReturn(java.util.Optional.empty());
    org.mockito.Mockito.lenient()
        .when(ownerScopeService.currentOrgUnit())
        .thenReturn(java.util.Optional.empty());

    // When
    Operation result = operationService.createOperation(operation, null);

    // Then
    assertNotNull(result);
    assertEquals("Test Op", result.getName());
    verify(operationRepository, times(1)).save(operation);
    verify(auditService)
        .record(
            eq(de.greluc.krt.profit.basetool.backend.model.AuditEventType.OPERATION_CREATED),
            any(),
            eq("Test Op"),
            isNull(),
            any());
  }

  @Test
  void createOperation_withResolvedCallerAndPickerOutput_delegatesToOwnerScopeResolver() {
    Operation operation = new Operation();
    operation.setName("Picker Op");
    operation.setStatus(OperationStatus.PLANNED);

    de.greluc.krt.profit.basetool.backend.model.User caller =
        new de.greluc.krt.profit.basetool.backend.model.User();
    caller.setId(UUID.randomUUID());
    de.greluc.krt.profit.basetool.backend.model.Squadron picked =
        new de.greluc.krt.profit.basetool.backend.model.Squadron();
    picked.setId(UUID.randomUUID());
    UUID pickedOrgUnitId = picked.getId();

    when(userService.getCurrentUser()).thenReturn(Optional.of(caller));
    when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(caller, pickedOrgUnitId))
        .thenReturn(picked);
    when(operationRepository.save(any(Operation.class))).thenAnswer(i -> i.getArguments()[0]);

    Operation saved = operationService.createOperation(operation, pickedOrgUnitId);

    assertEquals(picked, saved.getOwningOrgUnit(), "picker output must be honoured verbatim");
  }

  @Test
  void createOperation_membershiplessLeadershipCaller_stampsNullOwningOrgUnit() {
    // #500 / REQ-ORG-009: organisation leadership ("Bereichsleitung") belongs to no Staffel/SK but
    // may plan org-wide operations. The nullable picker resolver returns null for such a
    // membershipless caller (instead of 400ing), so the operation persists ownerless — visible to
    // organisation members-or-above (operations have no public escape).
    Operation operation = new Operation();
    operation.setName("Bereichsleitung-Operation");
    operation.setStatus(OperationStatus.PLANNED);

    User caller = new User();
    caller.setId(UUID.randomUUID());

    when(userService.getCurrentUser()).thenReturn(Optional.of(caller));
    when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(caller, null)).thenReturn(null);
    when(operationRepository.save(any(Operation.class))).thenAnswer(i -> i.getArguments()[0]);

    Operation saved = operationService.createOperation(operation, null);

    assertNull(saved.getOwningOrgUnit(), "membershipless leadership caller → ownerless operation");
    verify(operationRepository, times(1)).save(operation);
  }

  @Test
  void shouldGetOperationById() {
    // Given
    UUID id = UUID.randomUUID();
    Operation operation = new Operation();
    operation.setId(id);
    when(operationRepository.findById(id)).thenReturn(Optional.of(operation));

    // When
    Operation result = operationService.getOperationById(id);

    // Then
    assertNotNull(result);
    assertEquals(id, result.getId());
  }

  @Test
  void getOperationById_throwsNotFoundException_whenMissing() {
    UUID missing = UUID.randomUUID();
    when(operationRepository.findById(missing)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> operationService.getOperationById(missing));
  }

  @Test
  void shouldGetAllOperations() {
    // Given
    PageRequest pageable = PageRequest.of(0, 10);
    Page<Operation> page = new PageImpl<>(List.of(new Operation()));
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(true, null, Set.of()));
    when(operationRepository.findAllScoped(true, null, Set.of(), false, null, pageable))
        .thenReturn(page);

    // When
    Page<Operation> result = operationService.getAllOperations(pageable);

    // Then
    assertNotNull(result);
    assertEquals(1, result.getTotalElements());
  }

  // --- searchOperations ----------------------------------------------------

  @Nested
  class SearchOperationsTests {

    @Test
    void forwardsCallerSuppliedStatusList_andResolvesScopeFromSquadronService() {
      // The service must (1) honour the caller's status list verbatim and (2) read the squadron
      // scope through OwnerScopeService, NOT bypass it - operations are a strict-staffel
      // aggregate and a missing scope filter would leak other squadrons' operations.
      PageRequest pageable = PageRequest.of(0, 20);
      UUID squadronId = UUID.randomUUID();
      List<String> requestedStatus = List.of("PLANNED", "ACTIVE");

      when(ownerScopeService.currentScopePredicate())
          .thenReturn(new ScopePredicate(false, squadronId, Set.of()));
      when(operationRepository.searchOperations(
              "alpha",
              null,
              null,
              requestedStatus,
              false,
              squadronId,
              Set.of(),
              false,
              null,
              pageable))
          .thenReturn(new PageImpl<>(List.of(new Operation())));

      Page<Operation> result =
          operationService.searchOperations("alpha", null, null, requestedStatus, pageable);

      assertEquals(1, result.getTotalElements());
      verify(operationRepository, times(1))
          .searchOperations(
              "alpha",
              null,
              null,
              requestedStatus,
              false,
              squadronId,
              Set.of(),
              false,
              null,
              pageable);
    }

    @Test
    void nullStatusList_fallsBackToFullEnumSet() {
      // The repository query uses `status IN (:status)` and an empty list would yield no results;
      // the service must therefore expand `null`/empty to every OperationStatus name so callers
      // can omit the parameter to mean "all statuses".
      PageRequest pageable = PageRequest.of(0, 20);
      when(ownerScopeService.currentScopePredicate())
          .thenReturn(new ScopePredicate(true, null, Set.of()));
      ArgumentCaptor<List<String>> statusCaptor = ArgumentCaptor.captor();
      when(operationRepository.searchOperations(
              any(),
              any(),
              any(),
              statusCaptor.capture(),
              any(Boolean.class),
              any(),
              any(),
              any(Boolean.class),
              any(),
              any()))
          .thenReturn(new PageImpl<>(List.of()));

      operationService.searchOperations(null, null, null, null, pageable);

      List<String> forwarded = statusCaptor.getValue();
      assertTrue(forwarded.contains("PLANNED"));
      assertTrue(forwarded.contains("ACTIVE"));
      assertTrue(forwarded.contains("COMPLETED"));
      assertTrue(forwarded.contains("CANCELED"));
      assertEquals(4, forwarded.size(), "all four OperationStatus values must be forwarded");
    }

    @Test
    void emptyStatusList_alsoFallsBackToFullEnumSet() {
      // `List.of()` is a separate code path from `null` — both must produce the same fallback.
      PageRequest pageable = PageRequest.of(0, 20);
      when(ownerScopeService.currentScopePredicate())
          .thenReturn(new ScopePredicate(true, null, Set.of()));
      ArgumentCaptor<List<String>> statusCaptor = ArgumentCaptor.captor();
      when(operationRepository.searchOperations(
              any(),
              any(),
              any(),
              statusCaptor.capture(),
              any(Boolean.class),
              any(),
              any(),
              any(Boolean.class),
              any(),
              any()))
          .thenReturn(new PageImpl<>(List.of()));

      operationService.searchOperations(null, null, null, List.of(), pageable);

      assertEquals(4, statusCaptor.getValue().size());
    }

    @Test
    void adminAllSquadronsMode_passesNullScopeToRepository() {
      // OwnerScopeService.currentScopePredicate() returns adminAllScope=true for admins without
      // an active squadron selection ("all squadrons" mode). The service must forward that to the
      // repository so the JPA query disables the scope filter.
      PageRequest pageable = PageRequest.of(0, 20);
      when(ownerScopeService.currentScopePredicate())
          .thenReturn(new ScopePredicate(true, null, Set.of()));
      when(operationRepository.searchOperations(
              any(),
              any(),
              any(),
              any(),
              any(Boolean.class),
              any(),
              any(),
              any(Boolean.class),
              any(),
              any()))
          .thenReturn(new PageImpl<>(List.of()));

      operationService.searchOperations(null, null, null, List.of("PLANNED"), pageable);

      verify(operationRepository, times(1))
          .searchOperations(
              null, null, null, List.of("PLANNED"), true, null, Set.of(), false, null, pageable);
    }

    @Test
    void forwardsTimeRangeBoundsToRepositoryVerbatim() {
      // The start/end bounds filter on the operation's derived mission span (earliest planned
      // start / latest planned end). The service does no interpretation of its own — it forwards
      // both instants straight to the repository, whose CAST(... AS timestamp) IS NULL guard
      // disables a null bound.
      PageRequest pageable = PageRequest.of(0, 20);
      Instant start = Instant.parse("2026-06-01T00:00:00Z");
      Instant end = Instant.parse("2026-06-30T23:59:00Z");
      when(ownerScopeService.currentScopePredicate())
          .thenReturn(new ScopePredicate(true, null, Set.of()));
      when(operationRepository.searchOperations(
              any(),
              any(),
              any(),
              any(),
              any(Boolean.class),
              any(),
              any(),
              any(Boolean.class),
              any(),
              any()))
          .thenReturn(new PageImpl<>(List.of()));

      operationService.searchOperations(null, start, end, List.of("PLANNED"), pageable);

      verify(operationRepository, times(1))
          .searchOperations(
              null, start, end, List.of("PLANNED"), true, null, Set.of(), false, null, pageable);
    }
  }

  @Test
  void shouldDeleteOperation() {
    // Given
    UUID id = UUID.randomUUID();
    Operation operation = new Operation();
    when(operationRepository.findById(id)).thenReturn(Optional.of(operation));
    doNothing().when(operationRepository).delete(operation);

    // When
    operationService.deleteOperation(id);

    // Then
    verify(operationRepository, times(1)).delete(operation);
  }

  @Test
  void deleteOperation_unlinksMissions_butDoesNotDeleteThem() {
    // The contract of deleteOperation is to clear the mission -> operation
    // back-reference and clear the in-memory collection, then delete the
    // operation itself. Missions and everything hanging off them (participants,
    // finance entries, inventory items, refinery orders) MUST survive — only
    // the operation aggregate root vanishes.
    UUID id = UUID.randomUUID();
    Operation operation = new Operation();
    operation.setId(id);

    Mission m1 = new Mission();
    m1.setId(UUID.randomUUID());
    m1.setOperation(operation);
    Mission m2 = new Mission();
    m2.setId(UUID.randomUUID());
    m2.setOperation(operation);
    Set<Mission> missions = new HashSet<>();
    missions.add(m1);
    missions.add(m2);
    operation.setMissions(missions);

    when(operationRepository.findById(id)).thenReturn(Optional.of(operation));

    operationService.deleteOperation(id);

    assertNull(m1.getOperation(), "mission #1 back-reference to the operation must be cleared");
    assertNull(m2.getOperation(), "mission #2 back-reference to the operation must be cleared");
    assertTrue(
        operation.getMissions().isEmpty(),
        "in-memory missions collection must be cleared to keep state consistent");
    verify(operationRepository, times(1)).delete(operation);
  }

  @Test
  void deleteOperation_throwsNotFoundException_whenMissing() {
    UUID missing = UUID.randomUUID();
    when(operationRepository.findById(missing)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> operationService.deleteOperation(missing));
  }

  // --- updateOperation -----------------------------------------------------

  @Nested
  class UpdateOperationTests {

    @Test
    void updatesAllFields_whenVersionMatchesAndTransitionAllowed() {
      UUID id = UUID.randomUUID();
      Operation existing = new Operation();
      existing.setId(id);
      existing.setName("old");
      existing.setDescription("old-desc");
      existing.setStatus(OperationStatus.PLANNED);
      existing.setVersion(2L);

      // PLANNED -> ACTIVE is allowed by the state machine.
      OperationUpdateDto incoming =
          new OperationUpdateDto("new", "new-desc", OperationStatus.ACTIVE, 2L);

      when(operationRepository.findById(id)).thenReturn(Optional.of(existing));
      when(operationRepository.saveAndFlush(existing)).thenReturn(existing);

      Operation result = operationService.updateOperation(id, incoming, false);

      assertEquals("new", result.getName());
      assertEquals("new-desc", result.getDescription());
      assertEquals(OperationStatus.ACTIVE, result.getStatus());
    }

    @Test
    void rejectsForbiddenStatusTransition_whenNotAdmin() {
      // PLANNED -> COMPLETED skips the ACTIVE phase and is not a valid transition.
      UUID id = UUID.randomUUID();
      Operation existing = new Operation();
      existing.setId(id);
      existing.setStatus(OperationStatus.PLANNED);
      existing.setVersion(1L);

      OperationUpdateDto incoming = new OperationUpdateDto("n", "d", OperationStatus.COMPLETED, 1L);

      when(operationRepository.findById(id)).thenReturn(Optional.of(existing));

      BadRequestException ex =
          assertThrows(
              BadRequestException.class,
              () -> operationService.updateOperation(id, incoming, false));
      assertTrue(ex.getMessage().contains("PLANNED"));
      assertTrue(ex.getMessage().contains("COMPLETED"));
    }

    @Test
    void terminalStatusCannotBeChanged_whenNotAdmin() {
      // COMPLETED has no outgoing transitions.
      UUID id = UUID.randomUUID();
      Operation existing = new Operation();
      existing.setId(id);
      existing.setStatus(OperationStatus.COMPLETED);
      existing.setVersion(1L);

      OperationUpdateDto incoming = new OperationUpdateDto("n", "d", OperationStatus.ACTIVE, 1L);

      when(operationRepository.findById(id)).thenReturn(Optional.of(existing));

      assertThrows(
          BadRequestException.class, () -> operationService.updateOperation(id, incoming, false));
    }

    @Test
    void sameStatusIsAlwaysAllowed_evenWithoutAdmin() {
      // Updating only the name/description on a COMPLETED operation must NOT
      // trip the state-machine guard. Same-status transitions are always fine.
      UUID id = UUID.randomUUID();
      Operation existing = new Operation();
      existing.setId(id);
      existing.setName("old");
      existing.setStatus(OperationStatus.COMPLETED);
      existing.setVersion(1L);

      OperationUpdateDto incoming =
          new OperationUpdateDto("new", "post-mortem description", OperationStatus.COMPLETED, 1L);

      when(operationRepository.findById(id)).thenReturn(Optional.of(existing));
      when(operationRepository.saveAndFlush(existing)).thenReturn(existing);

      Operation result = operationService.updateOperation(id, incoming, false);

      assertEquals("new", result.getName());
      assertEquals(OperationStatus.COMPLETED, result.getStatus());
    }

    @Test
    void adminMayOverrideStatusMachine() {
      // ADMIN reverses a CANCELED operation back to PLANNED — disallowed for
      // regular MISSION_MANAGER callers, but the override flag opens the gate.
      UUID id = UUID.randomUUID();
      Operation existing = new Operation();
      existing.setId(id);
      existing.setStatus(OperationStatus.CANCELED);
      existing.setVersion(1L);

      OperationUpdateDto incoming = new OperationUpdateDto("n", "d", OperationStatus.PLANNED, 1L);

      when(operationRepository.findById(id)).thenReturn(Optional.of(existing));
      when(operationRepository.saveAndFlush(existing)).thenReturn(existing);

      Operation result = operationService.updateOperation(id, incoming, true);

      assertEquals(OperationStatus.PLANNED, result.getStatus());
    }

    @Test
    void throwsNotFoundException_whenIdMissing() {
      UUID missing = UUID.randomUUID();
      when(operationRepository.findById(missing)).thenReturn(Optional.empty());

      OperationUpdateDto dto = new OperationUpdateDto("n", "d", OperationStatus.PLANNED, 0L);
      assertThrows(
          NotFoundException.class, () -> operationService.updateOperation(missing, dto, false));
    }

    @Test
    void throwsOptimisticLockingFailure_whenVersionMismatch() {
      UUID id = UUID.randomUUID();
      Operation existing = new Operation();
      existing.setId(id);
      existing.setVersion(7L);

      OperationUpdateDto incoming = new OperationUpdateDto("n", "d", OperationStatus.PLANNED, 3L);

      when(operationRepository.findById(id)).thenReturn(Optional.of(existing));

      assertThrows(
          ObjectOptimisticLockingFailureException.class,
          () -> operationService.updateOperation(id, incoming, false));
    }

    @Test
    void acceptsNullVersionInIncoming_asBypassToken() {
      // Mirrors the bypass behavior used by other services: a null version on
      // the inbound DTO skips the explicit check (Hibernate still catches stale
      // writes via the UPDATE ... WHERE version=N fallback on commit).
      // Note: in practice OperationUpdateDto declares @NotNull on version so
      // this code path is guarded at the controller boundary; the service-
      // level branch still has to remain forgiving so internal callers can
      // bypass the check explicitly.
      UUID id = UUID.randomUUID();
      Operation existing = new Operation();
      existing.setId(id);
      existing.setVersion(5L);
      existing.setName("old");
      existing.setStatus(OperationStatus.PLANNED);

      // Same-status update with a null version on the DTO. The status gate
      // is a no-op (PLANNED -> PLANNED is always fine), and the manual
      // optimistic-lock check is skipped due to the null version.
      OperationUpdateDto incoming =
          new OperationUpdateDto("new", null, OperationStatus.PLANNED, null);

      when(operationRepository.findById(id)).thenReturn(Optional.of(existing));
      when(operationRepository.saveAndFlush(existing)).thenReturn(existing);

      Operation result = operationService.updateOperation(id, incoming, false);
      assertEquals("new", result.getName());
    }
  }

  @Nested
  class HasUnfinishedMissionsTests {

    @Test
    void returnsTrue_whenRepositoryReportsAtLeastOneUnfinishedMission() {
      UUID operationId = UUID.randomUUID();
      when(missionRepository.existsByOperationIdWithUnfinishedActualTime(operationId))
          .thenReturn(true);

      assertTrue(operationService.hasUnfinishedMissions(operationId));
      verify(missionRepository, times(1)).existsByOperationIdWithUnfinishedActualTime(operationId);
    }

    @Test
    void returnsFalse_whenRepositoryReportsAllMissionsFullyTimestamped() {
      UUID operationId = UUID.randomUUID();
      when(missionRepository.existsByOperationIdWithUnfinishedActualTime(operationId))
          .thenReturn(false);

      assertFalse(operationService.hasUnfinishedMissions(operationId));
    }
  }
}
