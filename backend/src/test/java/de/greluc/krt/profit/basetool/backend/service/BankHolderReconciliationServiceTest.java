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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.BankHolder;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link BankHolderReconciliationService} (REQ-BANK-029, ADR-0040): bank-role users
 * become active holders, a role-managed holder that lost the role is auto-deactivated, an
 * auto-deactivated holder regaining the role is reactivated, and manually registered holders are
 * never auto-touched.
 */
@ExtendWith(MockitoExtension.class)
class BankHolderReconciliationServiceTest {

  @Mock private UserRepository userRepository;
  @Mock private BankHolderRepository holderRepository;
  @Mock private BankAuditService bankAuditService;

  @InjectMocks private BankHolderReconciliationService service;

  @Test
  void reconcile_createsActiveRoleManagedHolderForABankRoleUserWithoutOne() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);
    user.setUsername("greluc");
    when(userRepository.findUserIdsByRoleCode("BANK_EMPLOYEE")).thenReturn(Set.of(userId));
    when(userRepository.findUserIdsByRoleCode("BANK_MANAGEMENT")).thenReturn(Set.of());
    when(holderRepository.findByUserIdIn(any())).thenReturn(List.of());
    when(userRepository.findAllById(any())).thenReturn(List.of(user));
    when(holderRepository.findByRoleManagedTrueAndActiveTrue()).thenReturn(List.of());
    when(holderRepository.save(any(BankHolder.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.reconcileAll();

    ArgumentCaptor<BankHolder> saved = ArgumentCaptor.forClass(BankHolder.class);
    verify(holderRepository).save(saved.capture());
    assertTrue(saved.getValue().isActive());
    assertTrue(saved.getValue().isRoleManaged());
    assertEquals("greluc", saved.getValue().getHandle());
    verify(bankAuditService)
        .record(eq(BankAuditEventType.HOLDER_REGISTERED), any(), any(), eq(userId), eq("greluc"));
  }

  @Test
  void reconcile_deactivatesRoleManagedHolderWhoseUserLostTheBankRole() {
    when(userRepository.findUserIdsByRoleCode("BANK_EMPLOYEE")).thenReturn(Set.of());
    when(userRepository.findUserIdsByRoleCode("BANK_MANAGEMENT")).thenReturn(Set.of());
    User formerStaff = new User();
    formerStaff.setId(UUID.randomUUID());
    formerStaff.setUsername("ex-banker");
    BankHolder holder = new BankHolder();
    holder.setUser(formerStaff);
    holder.setHandle("ex-banker");
    holder.setActive(true);
    holder.setRoleManaged(true);
    when(holderRepository.findByRoleManagedTrueAndActiveTrue()).thenReturn(List.of(holder));
    when(holderRepository.save(any(BankHolder.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.reconcileAll();

    assertFalse(holder.isActive());
    verify(holderRepository).save(holder);
    verify(bankAuditService)
        .record(eq(BankAuditEventType.HOLDER_DEACTIVATED), any(), any(), any(), eq("ex-banker"));
  }

  @Test
  void reconcile_reactivatesAnAutoDeactivatedHolderRegainingTheRole() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);
    user.setUsername("returner");
    BankHolder holder = new BankHolder();
    holder.setUser(user);
    holder.setHandle("returner");
    holder.setActive(false);
    holder.setRoleManaged(true);
    when(userRepository.findUserIdsByRoleCode("BANK_EMPLOYEE")).thenReturn(Set.of(userId));
    when(userRepository.findUserIdsByRoleCode("BANK_MANAGEMENT")).thenReturn(Set.of());
    when(holderRepository.findByUserIdIn(any())).thenReturn(List.of(holder));
    when(holderRepository.findByRoleManagedTrueAndActiveTrue()).thenReturn(List.of());
    when(holderRepository.save(any(BankHolder.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.reconcileAll();

    assertTrue(holder.isActive());
    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.HOLDER_REACTIVATED), any(), any(), eq(userId), eq("returner"));
  }

  @Test
  void reconcile_leavesAManuallyRegisteredHolderUntouched() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);
    user.setUsername("manual");
    BankHolder manual = new BankHolder();
    manual.setUser(user);
    manual.setHandle("manual");
    manual.setActive(true);
    manual.setRoleManaged(false);
    when(userRepository.findUserIdsByRoleCode("BANK_EMPLOYEE")).thenReturn(Set.of(userId));
    when(userRepository.findUserIdsByRoleCode("BANK_MANAGEMENT")).thenReturn(Set.of());
    when(holderRepository.findByUserIdIn(any())).thenReturn(List.of(manual));
    when(holderRepository.findByRoleManagedTrueAndActiveTrue()).thenReturn(List.of());

    service.reconcileAll();

    verify(holderRepository, never()).save(any());
    verify(bankAuditService, never()).record(any(), any(), any(), any(), any());
    assertFalse(manual.isRoleManaged(), "a manual holder is never flipped to role-managed");
  }
}
