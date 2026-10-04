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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.dto.ProfitCalculationDto;
import de.greluc.krt.profit.basetool.backend.service.ProfitCalculationService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ProfitCalculationControllerTest {

  @Mock private ProfitCalculationService profitCalculationService;

  @InjectMocks private ProfitCalculationController profitCalculationController;

  @Test
  void shouldGetProfitCalculation() {
    UUID shipId = UUID.randomUUID();
    List<String> systems = List.of("Stanton");
    ProfitCalculationDto dto = laranite(UUID.randomUUID());

    when(profitCalculationService.calculateProfit(shipId, systems)).thenReturn(List.of(dto));

    List<ProfitCalculationDto> result =
        profitCalculationController.getProfitCalculation(shipId, systems);

    assertNotNull(result);
    assertEquals(1, result.size());
    assertEquals("Laranite", result.get(0).materialName());
    verify(profitCalculationService, times(1)).calculateProfit(shipId, systems);
  }

  @Test
  void responseNamesTheBuyAndSellTerminalsOfEachRoute() throws Exception {
    UUID shipId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    when(profitCalculationService.calculateProfit(shipId, List.of("Stanton", "Pyro")))
        .thenReturn(List.of(laranite(materialId)));
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(profitCalculationController).build();

    mockMvc
        .perform(
            get("/api/v1/materials/profit-calculation")
                .param("shipId", shipId.toString())
                .param("starSystemNames", "Stanton", "Pyro"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].materialId").value(materialId.toString()))
        .andExpect(jsonPath("$[0].buyTerminalName").value("TDD Lorville"))
        .andExpect(jsonPath("$[0].buyTerminalLocation").value("Hurston · Lorville"))
        .andExpect(jsonPath("$[0].sellTerminalName").value("Admin - ARC-L1"))
        .andExpect(jsonPath("$[0].sellTerminalLocation").value("Stanton · ARC-L1"))
        .andExpect(jsonPath("$[0].maxProfitFullLoad").value(80));
  }

  private static ProfitCalculationDto laranite(UUID materialId) {
    return new ProfitCalculationDto(
        materialId,
        "Laranite",
        BigDecimal.valueOf(20),
        BigDecimal.valueOf(30),
        BigDecimal.valueOf(10),
        BigDecimal.valueOf(50),
        BigDecimal.valueOf(160),
        BigDecimal.valueOf(80),
        "TDD Lorville",
        "Hurston · Lorville",
        "Admin - ARC-L1",
        "Stanton · ARC-L1");
  }
}
