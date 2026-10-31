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

package de.greluc.krt.profit.basetool.backend.operation.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link OperationAccessPolicy#canClearPayout}: officer reach in the owning unit.
 */
@ExtendWith(MockitoExtension.class)
class OperationAccessPolicyClearPayoutTest {

  @Mock private OwnerScopeService ownerScopeService;
  @Mock private AuthHelperService authHelper;
  @Mock private OperationRepository operationRepository;

  private OperationAccessPolicy policy() {
    return new OperationAccessPolicy(ownerScopeService, authHelper, operationRepository);
  }

  private Operation operationOwnedBy(UUID operationId, UUID unitId) {
    Operation operation = new Operation();
    operation.setId(operationId);
    if (unitId != null) {
      Squadron unit = new Squadron();
      unit.setId(unitId);
      operation.setOwningOrgUnit(unit);
    }
    when(operationRepository.findById(operationId)).thenReturn(Optional.of(operation));
    return operation;
  }

  @Test
  void canClearPayout_SeatInOwningUnit_ShouldReturnTrue() {
    UUID operationId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    operationOwnedBy(operationId, unitId);
    when(ownerScopeService.canActAsOfficerInOwner(unitId)).thenReturn(true);

    assertTrue(policy().canClearPayout(operationId));
  }

  @Test
  void canClearPayout_SeatInOtherUnit_ShouldReturnFalse() {
    UUID operationId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    operationOwnedBy(operationId, unitId);
    when(ownerScopeService.canActAsOfficerInOwner(unitId)).thenReturn(false);

    assertFalse(policy().canClearPayout(operationId));
  }

  @Test
  void canClearPayout_OwnerlessOperation_AsksTheOwnerlessRuleOfTheScopeKernel() {
    UUID operationId = UUID.randomUUID();
    operationOwnedBy(operationId, null);
    when(ownerScopeService.canActAsOfficerInOwner(null)).thenReturn(false);

    assertFalse(policy().canClearPayout(operationId));
  }

  @Test
  void canClearPayout_UnknownOperation_ShouldReturnFalse() {
    UUID operationId = UUID.randomUUID();
    when(operationRepository.findById(operationId)).thenReturn(Optional.empty());

    assertFalse(policy().canClearPayout(operationId));
  }
}
