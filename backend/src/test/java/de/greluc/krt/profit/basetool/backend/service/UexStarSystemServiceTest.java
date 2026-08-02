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

import static de.greluc.krt.profit.basetool.backend.service.UexFetchResults.fetched;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.dto.uex.UexStarSystemDto;
import de.greluc.krt.profit.basetool.backend.integration.UexClient;
import de.greluc.krt.profit.basetool.backend.model.StarSystem;
import de.greluc.krt.profit.basetool.backend.repository.StarSystemRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UexStarSystemServiceTest {

  @Mock private UexClient uexClient;

  @Mock private StarSystemRepository starSystemRepository;

  @InjectMocks private UexStarSystemService uexStarSystemService;

  @Test
  void shouldProcessStarSystemDtoAndCreateNewStarSystem() {
    // Given
    UexStarSystemDto dto =
        UexStarSystemDto.builder()
            .id(1)
            .name("Stanton")
            .code("ST")
            .isAvailableLive(1)
            .wiki("https://starcitizen.tools/Stanton")
            .jurisdictionName("UEE")
            .factionName("UEE")
            .build();

    when(uexClient.getStarSystems()).thenReturn(fetched(List.of(dto)));
    when(starSystemRepository.findByIdSystem(1)).thenReturn(Optional.empty());
    when(starSystemRepository.findByName("Stanton")).thenReturn(Optional.empty());

    StarSystem savedSystem = new StarSystem();
    savedSystem.setId(UUID.randomUUID());
    savedSystem.setIdSystem(1);
    savedSystem.setName("Stanton");

    // Mock save for both the initial creation and the subsequent update
    when(starSystemRepository.save(any(StarSystem.class))).thenAnswer(i -> i.getArgument(0));

    // When
    uexStarSystemService.fetchAndProcessStarSystems();

    // Then
    ArgumentCaptor<StarSystem> systemCaptor = ArgumentCaptor.forClass(StarSystem.class);
    verify(starSystemRepository, atLeastOnce()).save(systemCaptor.capture());

    StarSystem captured = systemCaptor.getValue();
    assertEquals(1, captured.getIdSystem());
    assertEquals("Stanton", captured.getName());
    assertEquals(true, captured.getIsAvailableLive());
    assertEquals("https://starcitizen.tools/Stanton", captured.getWiki());
    assertEquals("UEE", captured.getJurisdictionName());
    assertEquals("UEE", captured.getFactionName());
  }
}
