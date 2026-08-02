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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.dto.uex.UexCategoryDto;
import de.greluc.krt.profit.basetool.backend.integration.UexClient;
import de.greluc.krt.profit.basetool.backend.model.UexCategory;
import de.greluc.krt.profit.basetool.backend.repository.UexCategoryRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link UexCategoryRefService}. */
@ExtendWith(MockitoExtension.class)
class UexCategoryRefServiceTest {

  @Mock private UexClient uexClient;
  @Mock private UexCategoryRepository repository;

  @InjectMocks private UexCategoryRefService service;

  @Test
  void syncCategories_emptyResponse_returnsLocalCatalogueAndDoesNotWrite() {
    when(uexClient.getCategories()).thenReturn(fetched(List.of()));
    when(repository.findAll()).thenReturn(List.of());

    List<UexCategory> result = service.syncCategories();

    assertTrue(result.isEmpty());
    verify(repository, never()).save(any());
  }

  @Test
  void syncCategories_insertsNewRowsAndUpdatesExisting() {
    UexCategoryDto helmets = new UexCategoryDto(3, "item", "Armor", "Helmets", 1, 0);
    UexCategoryDto torso = new UexCategoryDto(5, "item", "Armor", "Torso", 1, 0);
    when(uexClient.getCategories()).thenReturn(fetched(List.of(helmets, torso)));

    UexCategory existing = new UexCategory();
    existing.setId(3);
    existing.setSection("Armor");
    existing.setName("Old Helmet Label");
    existing.setIsGameRelated(false);
    existing.setIsMining(false);
    when(repository.findById(3)).thenReturn(Optional.of(existing));
    when(repository.findById(5)).thenReturn(Optional.empty());
    when(repository.save(any(UexCategory.class))).thenAnswer(inv -> inv.getArgument(0));
    when(repository.findAll()).thenReturn(List.of(existing));

    service.syncCategories();

    ArgumentCaptor<UexCategory> saved = ArgumentCaptor.forClass(UexCategory.class);
    verify(repository, times(2)).save(saved.capture());
    List<UexCategory> persisted = saved.getAllValues();
    assertEquals("Helmets", persisted.get(0).getName());
    assertEquals("Armor", persisted.get(0).getSection());
    assertTrue(persisted.get(0).getIsGameRelated());
    assertEquals(5, persisted.get(1).getId());
    assertNotNull(persisted.get(0).getUexSyncedAt());
  }

  @Test
  void syncCategories_skipsUnsupportedType_soTheCheckConstraintNeverFires() {
    // UEX returns 'service'/commodity categories (e.g. id 39, Trading/General) alongside item and
    // vehicle ones. uex_category.type is constrained to ('item','vehicle') (chk_uex_category_type),
    // so persisting a 'service' row would abort the whole sweep; it must be skipped before the DB.
    UexCategoryDto item = new UexCategoryDto(3, "item", "Armor", "Helmets", 1, 0);
    UexCategoryDto vehicle = new UexCategoryDto(7, "vehicle", "Systems", "Coolers", 1, 0);
    UexCategoryDto tradingCat = new UexCategoryDto(39, "service", "Trading", "General", 0, 0);
    when(uexClient.getCategories()).thenReturn(fetched(List.of(item, vehicle, tradingCat)));
    when(repository.findById(3)).thenReturn(Optional.empty());
    when(repository.findById(7)).thenReturn(Optional.empty());
    when(repository.save(any(UexCategory.class))).thenAnswer(inv -> inv.getArgument(0));
    when(repository.findAll()).thenReturn(List.of());

    service.syncCategories();

    // Only the item + vehicle rows reach the DB; the 'service' row is skipped before findById.
    ArgumentCaptor<UexCategory> saved = ArgumentCaptor.forClass(UexCategory.class);
    verify(repository, times(2)).save(saved.capture());
    verify(repository, never()).findById(39);
    assertEquals(
        List.of("item", "vehicle"),
        saved.getAllValues().stream().map(UexCategory::getType).toList(),
        "only item and vehicle categories are persisted; 'service' is skipped");
  }

  @Test
  void syncCategories_skipsRowWithMissingId() {
    UexCategoryDto missingId = new UexCategoryDto(null, "item", "Armor", "Helmets", 1, 0);
    when(uexClient.getCategories()).thenReturn(fetched(List.of(missingId)));
    when(repository.findAll()).thenReturn(List.of());

    service.syncCategories();

    verify(repository, never()).save(any());
  }
}
