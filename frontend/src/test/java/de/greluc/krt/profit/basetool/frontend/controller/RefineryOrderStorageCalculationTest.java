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

package de.greluc.krt.profit.basetool.frontend.controller;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyClass;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.*;
import de.greluc.krt.profit.basetool.frontend.model.form.RefineryOrderStoreForm;
import de.greluc.krt.profit.basetool.frontend.model.form.RefineryOrderStoreItemForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

@ExtendWith(MockitoExtension.class)
class RefineryOrderStorageCalculationTest {

  @Mock private BackendApiClient backendApiClient;

  @Mock private RoleHierarchy roleHierarchy;

  @InjectMocks private RefineryOrderPageController controller;

  @Mock private OidcUser oidcUser;

  private Model model;

  @BeforeEach
  void setUp() {
    model = new ExtendedModelMap();
  }

  @Test
  void testStoreFormCalculationForPiece() {
    UUID orderId = UUID.randomUUID();
    MaterialDto pieceMaterial =
        new MaterialDto(
            UUID.randomUUID(),
            "PieceMat",
            "TYPE",
            "PIECE",
            null,
            null,
            null,
            false,
            false,
            false,
            false,
            false,
            false,
            true,
            0L);
    RefineryGoodDto good =
        new RefineryGoodDto(UUID.randomUUID(), pieceMaterial, 100, pieceMaterial, 15, 100, null);
    RefineryOrderDto orderDto =
        new RefineryOrderDto(
            orderId,
            null,
            null,
            null,
            null,
            60L,
            0.0,
            0d,
            0d,
            0d,
            null,
            List.of(good),
            RefineryOrderStatus.OPEN,
            null,
            0L,
            null);

    when(backendApiClient.get(eq("/api/v1/refinery-orders/" + orderId), anyClass()))
        .thenReturn(orderDto);
    when(oidcUser.getSubject()).thenReturn(UUID.randomUUID().toString());

    controller.viewOrderDetail(orderId, model, oidcUser);

    RefineryOrderStoreForm storeForm = (RefineryOrderStoreForm) model.getAttribute("storeForm");
    assertNotNull(storeForm);
    assertEquals(1, storeForm.getItems().size());
    RefineryOrderStoreItemForm item = storeForm.getItems().get(0);

    assertEquals(15.0, item.getAmount());
    assertEquals("PIECE", item.getQuantityType());
    assertTrue(item.getAmountFixed());
  }

  @Test
  void testStoreFormCalculationForScu() {
    UUID orderId = UUID.randomUUID();
    MaterialDto scuMaterial =
        new MaterialDto(
            UUID.randomUUID(),
            "ScuMat",
            "TYPE",
            "SCU",
            null,
            null,
            null,
            false,
            false,
            false,
            false,
            false,
            false,
            true,
            0L);
    RefineryGoodDto good =
        new RefineryGoodDto(UUID.randomUUID(), scuMaterial, 100, scuMaterial, 1234, 100, null);
    RefineryOrderDto orderDto =
        new RefineryOrderDto(
            orderId,
            null,
            null,
            null,
            null,
            60L,
            0.0,
            0d,
            0d,
            0d,
            null,
            List.of(good),
            RefineryOrderStatus.OPEN,
            null,
            0L,
            null);

    when(backendApiClient.get(eq("/api/v1/refinery-orders/" + orderId), anyClass()))
        .thenReturn(orderDto);

    SystemSettingDto settingDto = new SystemSettingDto("refinery.rounding.mode", "HALF_UP", 0L);
    when(backendApiClient.get(eq("/api/v1/settings/refinery.rounding.mode"), anyClass()))
        .thenReturn(settingDto);
    when(oidcUser.getSubject()).thenReturn(UUID.randomUUID().toString());

    controller.viewOrderDetail(orderId, model, oidcUser);

    RefineryOrderStoreForm storeForm = (RefineryOrderStoreForm) model.getAttribute("storeForm");
    assertNotNull(storeForm);
    assertEquals(1, storeForm.getItems().size());
    RefineryOrderStoreItemForm item = storeForm.getItems().get(0);

    // 1234 / 100.0 = 12.34
    assertEquals(12.34, item.getAmount());
    assertEquals("SCU", item.getQuantityType());
    assertTrue(item.getAmountFixed());
  }

  /**
   * Regression test for issue #230. UEX-imported materials historically have a NULL {@code
   * quantity_type} (the UEX commodity sync never sets the field). Before the fix the store-dialog
   * prefill treated NULL as "not SCU" and skipped the units->SCU conversion, so a 2.21 SCU refinery
   * output got booked as 221 SCU. The controller now defaults the unknown/NULL case to SCU since
   * refineries never produce piece-counted goods.
   */
  @Test
  void testStoreFormCalculationForNullQuantityTypeDefaultsToScu() {
    UUID orderId = UUID.randomUUID();
    MaterialDto nullQuantityTypeMaterial =
        new MaterialDto(
            UUID.randomUUID(),
            "Ouratite",
            "REFINED",
            null, // ← reproduces the bug: UEX-imported material with NULL quantity_type
            null,
            null,
            null,
            false,
            false,
            false,
            false,
            false,
            false,
            true,
            0L);
    RefineryGoodDto good =
        new RefineryGoodDto(
            UUID.randomUUID(),
            nullQuantityTypeMaterial,
            100,
            nullQuantityTypeMaterial,
            221,
            100,
            null);
    RefineryOrderDto orderDto =
        new RefineryOrderDto(
            orderId,
            null,
            null,
            null,
            null,
            60L,
            0.0,
            0d,
            0d,
            0d,
            null,
            List.of(good),
            RefineryOrderStatus.OPEN,
            null,
            0L,
            null);

    when(backendApiClient.get(eq("/api/v1/refinery-orders/" + orderId), anyClass()))
        .thenReturn(orderDto);

    SystemSettingDto settingDto = new SystemSettingDto("refinery.rounding.mode", "HALF_UP", 0L);
    when(backendApiClient.get(eq("/api/v1/settings/refinery.rounding.mode"), anyClass()))
        .thenReturn(settingDto);
    when(oidcUser.getSubject()).thenReturn(UUID.randomUUID().toString());

    controller.viewOrderDetail(orderId, model, oidcUser);

    RefineryOrderStoreForm storeForm = (RefineryOrderStoreForm) model.getAttribute("storeForm");
    assertNotNull(storeForm);
    assertEquals(1, storeForm.getItems().size());
    RefineryOrderStoreItemForm item = storeForm.getItems().get(0);

    // 221 / 100.0 = 2.21 — the user's reproduction in issue #230. Pre-fix this asserted 221.0.
    assertEquals(2.21, item.getAmount());
    // The displayed unit label must follow the converted amount, otherwise the user sees
    // "2.21 Stück" instead of "2.21 SCU" and gets confused.
    assertEquals("SCU", item.getQuantityType());
    assertTrue(item.getAmountFixed());
  }
}
