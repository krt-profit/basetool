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

import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The one lookup a write uses to put a game item on an entry: it resolves the id like {@link
 * GameItemRepository#findById} and refuses an item the P4K retired (REQ-P4K-013).
 */
@Component
@RequiredArgsConstructor
public class SelectableGameItems {

  private final GameItemRepository gameItemRepository;

  /**
   * Loads the item a write sets on an entry.
   *
   * @param id the caller-supplied live or retained item id
   * @return the surviving item, never retired
   * @throws NotFoundException when the id names no item
   * @throws RetiredGameItemException when the P4K swept the item or an admin hid it
   */
  @NotNull
  public GameItem require(@NotNull UUID id) {
    return requireSelectable(
        Entities.require(gameItemRepository.findById(id), "Game item not found"));
  }

  /**
   * Refuses an item an entry is about to keep or take when the P4K retired it.
   *
   * @param item the item already loaded
   * @return {@code item}
   * @throws RetiredGameItemException when the P4K swept the item or an admin hid it
   */
  @NotNull
  @Contract("_ -> param1")
  public GameItem requireSelectable(@NotNull GameItem item) {
    if (item.isP4kRetired()) {
      throw new RetiredGameItemException(item.getId());
    }
    return item;
  }
}
