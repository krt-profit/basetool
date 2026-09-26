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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkOrgUnitChangeRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkOrgUnitChangeResultDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemOrgUnitChangeDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

/**
 * Pins the rules of changing a personal Lager row's org unit (REQ-INV-052): own personal rows only,
 * a direct membership or no unit as target, no event when nothing changes, and the merge rules
 * applied after the change.
 */
@ExtendWith(MockitoExtension.class)
class InventoryOrgUnitChangeServiceTest {

  private static final UUID CALLER = UUID.randomUUID();

  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private OrgUnitMembershipQueryService membershipQueryService;
  @Mock private InventoryCheckoutService inventoryCheckoutService;
  @Mock private InventoryItemMapper inventoryItemMapper;
  @Mock private AuditService auditService;

  private InventoryOrgUnitChangeService service;

  private Squadron alpha;

  @BeforeEach
  void setUp() {
    service =
        new InventoryOrgUnitChangeService(
            inventoryItemRepository,
            orgUnitRepository,
            membershipQueryService,
            inventoryCheckoutService,
            inventoryItemMapper,
            auditService);
    alpha = new Squadron();
    alpha.setId(UUID.randomUUID());
  }

  @Test
  void movesAnUnstampedPersonalRowOntoAMembershipAndMerges() {
    InventoryItem row = row(CALLER, true, null);
    stubRow(row);
    when(membershipQueryService.findDirectMembershipOrgUnitIds(CALLER))
        .thenReturn(Set.of(alpha.getId()));
    when(orgUnitRepository.findById(alpha.getId())).thenReturn(Optional.of(alpha));
    when(inventoryItemRepository.saveAndFlush(row)).thenReturn(row);
    when(inventoryCheckoutService.mergeStockIfRequested(row, true)).thenReturn(row);

    service.changeOrgUnit(
        row.getId(), new InventoryItemOrgUnitChangeDto(0L, alpha.getId(), true), CALLER);

    assertThat(row.getOwningOrgUnit()).isSameAs(alpha);
    InOrder order = inOrder(inventoryItemRepository, auditService, inventoryCheckoutService);
    order.verify(inventoryItemRepository).saveAndFlush(row);
    order
        .verify(auditService)
        .record(
            eq(AuditEventType.INVENTORY_ORG_UNIT_CHANGED),
            eq(row.getId()),
            any(),
            eq(CALLER),
            argThat(d -> d.toString().contains("fromOrgUnit=none")));
    order.verify(inventoryCheckoutService).mergeStockIfRequested(row, true);
  }

  @Test
  void clearsTheUnitWhenNoTargetIsGiven() {
    InventoryItem row = row(CALLER, true, alpha);
    stubRow(row);
    when(inventoryItemRepository.saveAndFlush(row)).thenReturn(row);
    when(inventoryCheckoutService.mergeStockIfRequested(row, false)).thenReturn(row);

    service.changeOrgUnit(row.getId(), new InventoryItemOrgUnitChangeDto(null, null, null), CALLER);

    assertThat(row.getOwningOrgUnit()).isNull();
    verify(membershipQueryService, never()).findDirectMembershipOrgUnitIds(any());
    verify(auditService)
        .record(
            eq(AuditEventType.INVENTORY_ORG_UNIT_CHANGED),
            eq(row.getId()),
            any(),
            eq(CALLER),
            argThat(d -> d.toString().contains("toOrgUnit=none")));
  }

  @Test
  void leavingTheUnitAsItIsChangesAndRecordsNothing() {
    InventoryItem row = row(CALLER, true, alpha);
    stubRow(row);
    when(membershipQueryService.findDirectMembershipOrgUnitIds(CALLER))
        .thenReturn(Set.of(alpha.getId()));
    when(orgUnitRepository.findById(alpha.getId())).thenReturn(Optional.of(alpha));

    service.changeOrgUnit(
        row.getId(), new InventoryItemOrgUnitChangeDto(0L, alpha.getId(), null), CALLER);

    verify(inventoryItemRepository, never()).saveAndFlush(any());
    verifyNoInteractions(auditService, inventoryCheckoutService);
  }

  @Test
  void refusesAUnitTheCallerIsNotAMemberOf() {
    InventoryItem row = row(CALLER, true, null);
    stubRow(row);
    when(membershipQueryService.findDirectMembershipOrgUnitIds(CALLER)).thenReturn(Set.of());

    assertThrows(
        BadRequestException.class,
        () ->
            service.changeOrgUnit(
                row.getId(), new InventoryItemOrgUnitChangeDto(0L, alpha.getId(), null), CALLER));
    verifyNoInteractions(auditService);
  }

  @Test
  void refusesASharedRow() {
    InventoryItem row = row(CALLER, false, alpha);
    stubRow(row);

    assertThrows(
        BadRequestException.class,
        () ->
            service.changeOrgUnit(
                row.getId(), new InventoryItemOrgUnitChangeDto(0L, null, null), CALLER));
  }

  @Test
  void refusesAnotherMembersRow() {
    InventoryItem row = row(UUID.randomUUID(), true, null);
    stubRow(row);

    assertThrows(
        AccessDeniedException.class,
        () ->
            service.changeOrgUnit(
                row.getId(), new InventoryItemOrgUnitChangeDto(0L, null, null), CALLER));
  }

  @Test
  void refusesAStaleVersion() {
    InventoryItem row = row(CALLER, true, null);
    row.setVersion(3L);
    stubRow(row);

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () ->
            service.changeOrgUnit(
                row.getId(), new InventoryItemOrgUnitChangeDto(2L, null, null), CALLER));
  }

  @Test
  void bulkChangesDifferingRowsSkipsTheRestAndRecordsOneSummary() {
    InventoryItem moving = row(CALLER, true, null);
    InventoryItem already = row(CALLER, true, alpha);
    stubRow(moving);
    stubRow(already);
    when(membershipQueryService.findDirectMembershipOrgUnitIds(CALLER))
        .thenReturn(Set.of(alpha.getId()));
    when(orgUnitRepository.findById(alpha.getId())).thenReturn(Optional.of(alpha));
    when(inventoryItemRepository.saveAndFlush(moving)).thenReturn(moving);

    BulkOrgUnitChangeResultDto result =
        service.bulkChangeOrgUnit(
            new BulkOrgUnitChangeRequest(
                List.of(moving.getId(), already.getId(), moving.getId()), alpha.getId(), false),
            CALLER);

    assertThat(result.changed()).isEqualTo(1);
    assertThat(result.skipped()).isEqualTo(1);
    verify(inventoryCheckoutService).mergeStockIfRequested(moving, false);
    verify(auditService)
        .record(
            eq(AuditEventType.INVENTORY_BULK_ORG_UNIT_CHANGED),
            isNull(),
            isNull(),
            eq(CALLER),
            argThat(d -> d.toString().contains("changed=1")));
  }

  @Test
  void bulkRefusesTheWholeSelectionWhenOneRowIsShared() {
    InventoryItem personal = row(CALLER, true, null);
    InventoryItem shared = row(CALLER, false, null);
    stubRow(personal);
    stubRow(shared);

    assertThrows(
        BadRequestException.class,
        () ->
            service.bulkChangeOrgUnit(
                new BulkOrgUnitChangeRequest(
                    List.of(personal.getId(), shared.getId()), null, false),
                CALLER));
    verify(inventoryItemRepository, never()).saveAndFlush(any());
    verifyNoInteractions(auditService);
  }

  /**
   * Builds a row owned by {@code ownerId}.
   *
   * @param ownerId the owner
   * @param personal the personal marker
   * @param orgUnit the owning unit, or {@code null}
   * @return the row
   */
  private static @NotNull InventoryItem row(
      @NotNull UUID ownerId, boolean personal, @Nullable Squadron orgUnit) {
    User owner = new User();
    owner.setId(ownerId);
    InventoryItem item = new InventoryItem();
    item.setId(UUID.randomUUID());
    item.setUser(owner);
    item.setPersonal(personal);
    item.setOwningOrgUnit(orgUnit);
    return item;
  }

  /**
   * Makes the locked lookup return {@code row}.
   *
   * @param row the row
   */
  private void stubRow(@NotNull InventoryItem row) {
    when(inventoryItemRepository.findByIdForRebook(row.getId())).thenReturn(Optional.of(row));
  }
}
