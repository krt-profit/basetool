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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintOverviewEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintOverviewOwnerDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.service.PersonalBlueprintOverviewService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Unit tests for {@link PersonalBlueprintOverviewController}: page-envelope wrapping and
 * delegation.
 */
@ExtendWith(MockitoExtension.class)
class PersonalBlueprintOverviewControllerTest {

  @Mock private PersonalBlueprintOverviewService service;
  @InjectMocks private PersonalBlueprintOverviewController controller;

  @Test
  void list_wrapsServicePageIntoPageResponse() {
    Page<BlueprintOverviewEntryDto> page =
        new PageImpl<>(
            List.of(new BlueprintOverviewEntryDto("aurora", "Aurora MR", 3L)),
            PageRequest.of(0, 50, Sort.by("productName")),
            1);
    when(service.listAvailableBlueprints(any(), any())).thenReturn(page);

    PageResponse<BlueprintOverviewEntryDto> result =
        controller.listAvailableBlueprints(0, 50, null, null);

    assertEquals(1, result.totalElements());
    assertEquals("Aurora MR", result.content().get(0).productName());
    assertEquals(3L, result.content().get(0).ownerCount());
    verify(service).listAvailableBlueprints(any(), any());
  }

  @Test
  void list_relaysSearchToService() {
    when(service.listAvailableBlueprints(any(), eq("aurora")))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10, Sort.by("productName")), 0));

    controller.listAvailableBlueprints(0, 10, null, "aurora");

    verify(service).listAvailableBlueprints(any(), eq("aurora"));
  }

  @Test
  void owners_relaysProductKeyToService() {
    when(service.listOwnersForProduct("aurora"))
        .thenReturn(List.of(new BlueprintOverviewOwnerDto("Alpha", true)));

    List<BlueprintOverviewOwnerDto> result = controller.listOwners("aurora");

    assertEquals(1, result.size());
    assertEquals("Alpha", result.get(0).ownerName());
    verify(service).listOwnersForProduct("aurora");
  }
}
