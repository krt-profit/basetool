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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.service.BlueprintService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/** Unit tests for {@link BlueprintController}. */
@ExtendWith(MockitoExtension.class)
class BlueprintControllerTest {

  @Mock private BlueprintService blueprintService;
  @InjectMocks private BlueprintController blueprintController;

  @Test
  void getBlueprints_relaysFilterAndWrapsInPageResponse() {
    BlueprintDto dto = minimalDto();
    when(blueprintService.getBlueprints(eq("omni"), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(dto)));

    PageResponse<BlueprintDto> response = blueprintController.getBlueprints("omni", 0, 10, null);

    assertEquals(1, response.content().size());
    assertEquals("Omnisky", response.content().get(0).outputName());
  }

  @Test
  void getBlueprints_rejectsNonWhitelistedSortField() {
    assertThrows(
        IllegalArgumentException.class,
        () -> blueprintController.getBlueprints(null, 0, 10, "maliciousField"));
  }

  private static BlueprintDto minimalDto() {
    return new BlueprintDto(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "BP",
        "Omnisky",
        540,
        false,
        2,
        1,
        "4.8",
        null,
        null,
        null,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        0L);
  }
}
