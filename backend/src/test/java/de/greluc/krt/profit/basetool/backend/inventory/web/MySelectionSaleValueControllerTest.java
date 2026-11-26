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

package de.greluc.krt.profit.basetool.backend.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventorySelectionSaleValueDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventorySelectionValuation;
import de.greluc.krt.profit.basetool.backend.inventory.internal.MySelectionSaleValueRequest;
import de.greluc.krt.profit.basetool.backend.inventory.internal.SalePriceBasis;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Unit tests for {@link MySelectionSaleValueController} (REQ-INV-072): the caller comes from the
 * JWT and the marked ids are valued for that caller only.
 */
@ExtendWith(MockitoExtension.class)
class MySelectionSaleValueControllerTest {

  private static final UUID CALLER = UUID.randomUUID();

  @Mock private UserService userService;
  @Mock private InventorySelectionValuation selectionValuation;
  @Mock private Jwt jwt;
  @InjectMocks private MySelectionSaleValueController controller;

  @Test
  void valuesTheMarkedIdsForTheCaller() {
    List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
    InventorySelectionSaleValueDto value =
        new InventorySelectionSaleValueDto(
            SalePriceBasis.AVERAGE, new BigDecimal("1200"), 1, false);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER);
    when(selectionValuation.valueOf(CALLER, ids)).thenReturn(value);

    assertThat(controller.valueSelection(jwt, new MySelectionSaleValueRequest(ids)))
        .isSameAs(value);
  }
}
