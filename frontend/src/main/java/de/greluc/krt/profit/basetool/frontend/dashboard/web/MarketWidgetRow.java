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

package de.greluc.krt.profit.basetool.frontend.dashboard.web;

import de.greluc.krt.profit.basetool.frontend.materialexchange.model.MaterialExchangeOfferDto;
import de.greluc.krt.profit.basetool.frontend.materialexchange.model.MaterialRequestDto;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One line of the Materialbörse widget (REQ-UI-034): an offer or a wanted-listing reduced to what
 * the widget shows.
 *
 * @param id the offer or request
 * @param offer {@code true} for an offer, {@code false} for a wanted-listing
 * @param name the material or item name
 * @param item whether the line is a craftable item rather than a material
 * @param quality the quality of an offer or the floor of a wanted-listing, {@code null} for items
 * @param quantity the quantity offered or wanted
 * @param pieces whether the quantity counts pieces instead of SCU
 * @param at when the entry was released or posted
 */
record MarketWidgetRow(
    @NotNull UUID id,
    boolean offer,
    @NotNull String name,
    boolean item,
    @Nullable Integer quality,
    double quantity,
    boolean pieces,
    @Nullable Instant at) {

  private static final String QUANTITY_PIECE = "PIECE";

  private static final String KIND_ITEM = "ITEM";

  /**
   * Reduces an offer to a line.
   *
   * @param dto the offer
   * @return the line
   */
  static @NotNull MarketWidgetRow of(@NotNull MaterialExchangeOfferDto dto) {
    boolean isItem = KIND_ITEM.equals(dto.kind());
    return new MarketWidgetRow(
        dto.id(),
        true,
        isItem ? dto.itemName() : dto.material().name(),
        isItem,
        isItem ? null : dto.quality(),
        isItem ? count(dto.itemQuantity()) : count(dto.amount()),
        isItem || QUANTITY_PIECE.equals(dto.material().quantityType()),
        dto.releasedAt());
  }

  /**
   * Reduces a wanted-listing to a line.
   *
   * @param dto the wanted-listing
   * @return the line
   */
  static @NotNull MarketWidgetRow of(@NotNull MaterialRequestDto dto) {
    boolean isItem = KIND_ITEM.equals(dto.kind());
    return new MarketWidgetRow(
        dto.id(),
        false,
        isItem ? dto.itemName() : dto.material().name(),
        isItem,
        isItem ? null : dto.minQuality(),
        isItem ? count(dto.itemQuantity()) : count(dto.requestedAmount()),
        isItem || QUANTITY_PIECE.equals(dto.material().quantityType()),
        dto.postedAt());
  }

  /**
   * The newest lines first.
   *
   * @param rows the lines of both boards
   * @param limit the most lines to keep
   * @return at most {@code limit} lines, newest first, undated ones last
   */
  static @NotNull List<MarketWidgetRow> newest(@NotNull List<MarketWidgetRow> rows, int limit) {
    return rows.stream()
        .sorted(
            Comparator.comparing(
                MarketWidgetRow::at, Comparator.nullsLast(Comparator.reverseOrder())))
        .limit(limit)
        .toList();
  }

  private static double count(@Nullable Number value) {
    return value == null ? 0.0 : value.doubleValue();
  }
}
