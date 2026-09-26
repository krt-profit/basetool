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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link InventoryOrgUnitReconciler} — the re-stamp of a user's shared inventory
 * when they cross the membershipless boundary. Pins the auto-promote (NULL → org) and auto-demote
 * (org → NULL) policies and, crucially, that re-stamping ONLY changes {@code owningOrgUnit}: rows
 * that become identical after the re-stamp stay separate (inventory is append-only and the Lager
 * view collapses same-identity rows for display), amounts are never touched and nothing is ever
 * deleted.
 */
@ExtendWith(MockitoExtension.class)
class InventoryOrgUnitReconcilerTest {

  @Mock private InventoryItemRepository inventoryItemRepository;

  @Mock private AuditService auditService;
  @InjectMocks private InventoryOrgUnitReconciler reconciler;

  private Material matA;
  private Material matB;
  private Location loc;

  @BeforeEach
  void setUp() {
    matA = new Material();
    matA.setId(UUID.randomUUID());
    matB = new Material();
    matB.setId(UUID.randomUUID());
    loc = new Location();
    loc.setId(UUID.randomUUID());
  }

  @Test
  void onUserGainedFirstOrgUnit_stampsEveryOwnerlessSharedRowWithTheNewOrg() {
    UUID userId = UUID.randomUUID();
    OrgUnit org = squadron();
    InventoryItem r1 = sharedRow(null, 5.0, matA, 900);
    InventoryItem r2 = sharedRow(null, 3.0, matB, 800);
    when(inventoryItemRepository.findByUserIdAndPersonalFalse(userId)).thenReturn(List.of(r1, r2));

    reconciler.onUserGainedFirstOrgUnit(userId, org);

    assertSame(org, r1.getOwningOrgUnit(), "ownerless row adopts the new org unit");
    assertSame(org, r2.getOwningOrgUnit());
    verify(inventoryItemRepository, never()).delete(any());
  }

  @Test
  void onUserLostLastOrgUnit_demotesToNullWithoutMerging() {
    UUID userId = UUID.randomUUID();
    OrgUnit orgA = squadron();
    OrgUnit orgB = squadron();
    InventoryItem a = sharedRow(orgA, 4.0, matA, 900);
    InventoryItem b = sharedRow(orgB, 6.0, matA, 900);
    InventoryItem c = sharedRow(orgA, 2.0, matB, 900);
    when(inventoryItemRepository.findByUserIdAndPersonalFalse(userId)).thenReturn(List.of(a, b, c));

    reconciler.onUserLostLastOrgUnit(userId);

    assertNull(a.getOwningOrgUnit());
    assertNull(b.getOwningOrgUnit());
    assertNull(c.getOwningOrgUnit());
    assertEquals(4.0, a.getAmount(), "amounts are never merged or changed");
    assertEquals(6.0, b.getAmount(), "the would-be colliding row keeps its own amount");
    assertEquals(2.0, c.getAmount());
    verify(inventoryItemRepository, never()).delete(any());
  }

  @Test
  void onUserLostLastOrgUnit_distinctStacks_demoteWithoutMerging() {
    UUID userId = UUID.randomUUID();
    OrgUnit orgA = squadron();
    InventoryItem a = sharedRow(orgA, 4.0, matA, 900);
    InventoryItem b = sharedRow(orgA, 6.0, matB, 900);
    when(inventoryItemRepository.findByUserIdAndPersonalFalse(userId)).thenReturn(List.of(a, b));

    reconciler.onUserLostLastOrgUnit(userId);

    assertNull(a.getOwningOrgUnit());
    assertNull(b.getOwningOrgUnit());
    assertEquals(4.0, a.getAmount());
    assertEquals(6.0, b.getAmount());
    verify(inventoryItemRepository, never()).delete(any());
  }

  @Test
  void reconcile_noSharedRows_isNoOp() {
    UUID userId = UUID.randomUUID();
    when(inventoryItemRepository.findByUserIdAndPersonalFalse(userId)).thenReturn(List.of());

    reconciler.onUserLostLastOrgUnit(userId);

    verify(inventoryItemRepository, never()).delete(any());
  }

  @Test
  void gainedFirstOrgUnit_recordsRestampAudit() {
    UUID userId = UUID.randomUUID();
    OrgUnit org = squadron();
    InventoryItem row = sharedRow(null, 5.0, matA, 900);
    when(inventoryItemRepository.findByUserIdAndPersonalFalse(userId)).thenReturn(List.of(row));

    reconciler.onUserGainedFirstOrgUnit(userId, org);

    verify(auditService)
        .record(eq(AuditEventType.INVENTORY_ORG_RESTAMPED), isNull(), isNull(), eq(userId), any());
  }

  @Test
  void lostLastOrgUnit_recordsRestampAudit() {
    UUID userId = UUID.randomUUID();
    InventoryItem row = sharedRow(squadron(), 5.0, matA, 900);
    when(inventoryItemRepository.findByUserIdAndPersonalFalse(userId)).thenReturn(List.of(row));

    reconciler.onUserLostLastOrgUnit(userId);

    verify(auditService)
        .record(eq(AuditEventType.INVENTORY_ORG_RESTAMPED), isNull(), isNull(), eq(userId), any());
  }

  @Test
  void gainedFirstOrgUnit_noSharedRows_recordsNoAudit() {
    UUID userId = UUID.randomUUID();
    when(inventoryItemRepository.findByUserIdAndPersonalFalse(userId)).thenReturn(List.of());

    reconciler.onUserGainedFirstOrgUnit(userId, squadron());

    verify(auditService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void lostLast_allAlreadyNull_recordsNoAudit() {
    UUID userId = UUID.randomUUID();
    InventoryItem a = sharedRow(null, 4.0, matA, 900);
    InventoryItem b = sharedRow(null, 6.0, matB, 900);
    when(inventoryItemRepository.findByUserIdAndPersonalFalse(userId)).thenReturn(List.of(a, b));

    reconciler.onUserLostLastOrgUnit(userId);

    assertNull(a.getOwningOrgUnit());
    assertNull(b.getOwningOrgUnit());
    verify(auditService, never()).record(any(), any(), any(), any(), any());
  }

  private static OrgUnit squadron() {
    Squadron s = new Squadron();
    s.setId(UUID.randomUUID());
    return s;
  }

  private InventoryItem sharedRow(OrgUnit org, double amount, Material material, int quality) {
    InventoryItem i = new InventoryItem();
    i.setId(UUID.randomUUID());
    i.setOwningOrgUnit(org);
    i.setAmount(amount);
    i.setMaterial(material);
    i.setLocation(loc);
    i.setQuality(quality);
    i.setPersonal(false);
    return i;
  }
}
