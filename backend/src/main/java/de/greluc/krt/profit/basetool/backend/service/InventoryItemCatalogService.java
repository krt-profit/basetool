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

import de.greluc.krt.profit.basetool.backend.kernel.LikePatterns;
import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read service behind the game-item catalogue {@code GET /api/v1/game-items} (REQ-INV-029): lists
 * the game items bookable as Lager item stock, i.e. the output of at least one active blueprint
 * ({@link BlueprintRepository#findItemsWithActiveBlueprint(String, Pageable)}), or the narrower set
 * orderable as job-order item lines ({@link BlueprintRepository#findOrderableItems(String,
 * Pageable)}).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryItemCatalogService {

  private final BlueprintRepository blueprintRepository;
  private final InventoryItemMapper inventoryItemMapper;

  /**
   * Pages the game items bookable as Lager item stock, optionally filtered by name, as slim Lager
   * references (id, name, manufacturer, kind).
   *
   * @param search case-insensitive name substring, or {@code null}/blank for no filter
   * @param pageable page request (whitelisted {@code name} sort)
   * @return a page of bookable game-item references; never {@code null}
   */
  @NotNull
  public Page<InventoryGameItemReferenceDto> findBookableItems(
      String search, @NotNull Pageable pageable) {
    return blueprintRepository
        .findItemsWithActiveBlueprint(likeTerm(search), pageable)
        .map(inventoryItemMapper::gameItemToReferenceDto);
  }

  /**
   * Pages the game items orderable as job-order item lines (output of an active blueprint with at
   * least one resolved resource ingredient), optionally filtered by name.
   *
   * @param search case-insensitive name substring, or {@code null}/blank for no filter
   * @param pageable page request (whitelisted {@code name} / {@code id} sort)
   * @return a page of orderable game-item references; never {@code null}
   */
  @NotNull
  public Page<InventoryGameItemReferenceDto> findOrderableItems(
      String search, @NotNull Pageable pageable) {
    return blueprintRepository
        .findOrderableItems(likeTerm(search), pageable)
        .map(inventoryItemMapper::gameItemToReferenceDto);
  }

  /**
   * Turns a user search term into the escaped LIKE operand, {@code ""} for no filter.
   *
   * @param search the raw term, or {@code null}
   * @return the escaped, stripped term
   */
  @NotNull
  private static String likeTerm(String search) {
    return search != null && !search.isBlank() ? LikePatterns.escape(search.strip()) : "";
  }
}
