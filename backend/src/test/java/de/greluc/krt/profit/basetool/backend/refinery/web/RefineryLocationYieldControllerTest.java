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

package de.greluc.krt.profit.basetool.backend.refinery.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrderService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.RequestMapping;

/** Unit tests for {@link RefineryLocationYieldController}, the yield matrix under a location. */
@ExtendWith(MockitoExtension.class)
class RefineryLocationYieldControllerTest {

  @Mock private RefineryOrderService service;
  @InjectMocks private RefineryLocationYieldController controller;

  @Test
  void getYieldsForLocation_delegatesToService_andReturnsTheMap() {
    UUID locationId = UUID.randomUUID();
    Map<UUID, Integer> expected = Map.of(UUID.randomUUID(), 5, UUID.randomUUID(), -3);
    when(service.getYieldBonusByMaterialForLocationId(locationId)).thenReturn(expected);

    Map<UUID, Integer> result = controller.getYieldsForLocation(locationId);

    assertThat(result).isSameAs(expected);
    verify(service).getYieldBonusByMaterialForLocationId(locationId);
  }

  @Test
  void theMatrixIsServedUnderTheLocation() {
    RequestMapping mapping =
        RefineryLocationYieldController.class.getAnnotation(RequestMapping.class);

    assertThat(mapping.value()).containsExactly("/api/v1/locations/{locationId}/refinery-yields");
  }
}
