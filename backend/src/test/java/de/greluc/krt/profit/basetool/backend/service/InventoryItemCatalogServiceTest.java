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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
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

/**
 * Unit tests for {@link InventoryItemCatalogService#findBookableItems} — the Lager item-catalog
 * picker read (REQ-INV-029, design §5.3/§5.4). Two behaviours need a pin: the blank-search
 * normalisation to the empty string (a {@code null} bind into the query's {@code
 * LOWER(CONCAT(...))} makes PostgreSQL infer {@code bytea} and fail at runtime — invisible to a
 * mock unless the argument is captured), and the projection through {@code
 * InventoryItemMapper.gameItemToReferenceDto}.
 */
@ExtendWith(MockitoExtension.class)
class InventoryItemCatalogServiceTest {

  @Mock private BlueprintRepository blueprintRepository;
  @Mock private InventoryItemMapper inventoryItemMapper;
  @InjectMocks private InventoryItemCatalogService service;

  // covers REQ-INV-029 (item-catalog picker: bookable = output of >= 1 active blueprint)
  @Test
  void findBookableItems_projectsRepositoryPageThroughTheMapper() {
    // Given one bookable game item behind the active-blueprint query
    GameItem drive = new GameItem();
    drive.setId(UUID.randomUUID());
    drive.setName("Quantum Drive");
    Pageable pageable = PageRequest.of(0, 20);
    Page<GameItem> page = new PageImpl<>(List.of(drive), pageable, 1);
    InventoryGameItemReferenceDto ref =
        new InventoryGameItemReferenceDto(drive.getId(), "Quantum Drive", "RSI", "QUANTUM_DRIVE");
    when(blueprintRepository.findItemsWithActiveBlueprint("Quantum", pageable)).thenReturn(page);
    when(inventoryItemMapper.gameItemToReferenceDto(drive)).thenReturn(ref);

    // When
    Page<InventoryGameItemReferenceDto> result = service.findBookableItems(" Quantum ", pageable);

    // Then — the search fragment is stripped and each entity maps to its slim reference DTO
    assertThat(result.getContent()).containsExactly(ref);
    assertThat(result.getTotalElements()).isEqualTo(1L);
    verify(blueprintRepository).findItemsWithActiveBlueprint("Quantum", pageable);
  }

  // covers REQ-INV-029 (blank search binds "" — never null — into the LIKE pattern)
  @Test
  void findBookableItems_blankSearch_bindsEmptyStringNotNull() {
    // Given a blank search input
    Pageable pageable = PageRequest.of(0, 20);
    when(blueprintRepository.findItemsWithActiveBlueprint(eq(""), eq(pageable)))
        .thenReturn(Page.empty(pageable));

    // When
    service.findBookableItems("   ", pageable);
    service.findBookableItems(null, pageable);

    // Then — both blank and null normalise to "" (a null bind would make PostgreSQL infer bytea
    // inside LOWER(CONCAT(...)) and fail the picker at runtime)
    verify(blueprintRepository, org.mockito.Mockito.times(2))
        .findItemsWithActiveBlueprint("", pageable);
  }
}
