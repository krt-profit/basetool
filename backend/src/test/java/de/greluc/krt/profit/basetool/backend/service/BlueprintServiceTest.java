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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Blueprint;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.BlueprintService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** Unit tests for {@link BlueprintService} filter normalisation and DTO mapping. */
@ExtendWith(MockitoExtension.class)
class BlueprintServiceTest {

  @Mock private BlueprintRepository blueprintRepository;
  @Mock private BlueprintMapper blueprintMapper;
  @InjectMocks private BlueprintService blueprintService;

  @Test
  void getBlueprints_blankSearch_usesUnfilteredQueryAndMaps() {
    Blueprint bp = new Blueprint();
    when(blueprintRepository.findByScwikiDeletedAtIsNull(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(bp)));
    when(blueprintMapper.toDto(bp)).thenReturn(minimalDto());

    Page<BlueprintDto> result = blueprintService.getBlueprints("   ", PageRequest.of(0, 10));

    assertEquals(1, result.getContent().size());
    verify(blueprintRepository).findByScwikiDeletedAtIsNull(any(Pageable.class));
  }

  @Test
  void getBlueprints_nullSearch_usesUnfilteredQuery() {
    when(blueprintRepository.findByScwikiDeletedAtIsNull(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    blueprintService.getBlueprints(null, PageRequest.of(0, 10));

    verify(blueprintRepository).findByScwikiDeletedAtIsNull(any(Pageable.class));
  }

  @Test
  void getBlueprints_trimsSearchBeforeQuerying() {
    when(blueprintRepository.searchActive(eq("omni"), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    blueprintService.getBlueprints("  omni  ", PageRequest.of(0, 10));

    verify(blueprintRepository).searchActive(eq("omni"), any(Pageable.class));
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
        15,
        0.5,
        null,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        0L);
  }
}
