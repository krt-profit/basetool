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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/** The shared lookup that refuses a P4K-retired game item on a write (REQ-P4K-013). */
@ExtendWith(MockitoExtension.class)
class SelectableGameItemsTest {

  @Mock private GameItemRepository gameItemRepository;

  private SelectableGameItems lookup;

  @BeforeEach
  void setUp() {
    lookup = new SelectableGameItems(gameItemRepository);
  }

  @Test
  void aListedItemIsReturned() {
    GameItem item = item();
    when(gameItemRepository.findById(item.getId())).thenReturn(Optional.of(item));

    assertThat(lookup.require(item.getId())).isSameAs(item);
  }

  @Test
  void anUnknownIdIsNotFound() {
    UUID id = UUID.randomUUID();
    when(gameItemRepository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> lookup.require(id)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void anItemTheSweepStampedIsRefusedWithTheCatalogueCode() {
    GameItem item = item();
    item.setP4kDeletedAt(Instant.now());
    when(gameItemRepository.findById(item.getId())).thenReturn(Optional.of(item));

    assertThatThrownBy(() -> lookup.require(item.getId()))
        .isInstanceOfSatisfying(
            RetiredGameItemException.class,
            e -> {
              assertThat(e.code()).isEqualTo("CATALOGUE_ITEM_RETIRED");
              assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(e.extraProperties()).containsEntry("gameItemId", item.getId().toString());
            });
  }

  @Test
  void anItemAnAdminHidIsRefusedWhenAnEntryKeepsIt() {
    GameItem item = item();
    item.setP4kHiddenAt(Instant.now());

    assertThatThrownBy(() -> lookup.requireSelectable(item))
        .isInstanceOf(RetiredGameItemException.class);
  }

  private static GameItem item() {
    GameItem item = new GameItem();
    item.setId(UUID.randomUUID());
    item.setName("Arclight");
    return item;
  }
}
