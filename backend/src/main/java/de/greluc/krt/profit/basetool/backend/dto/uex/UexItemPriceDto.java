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

package de.greluc.krt.profit.basetool.backend.dto.uex;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * UEX Corp {@code /items_prices_all} row, mapped onto {@code game_item_price} by {@code
 * UexItemPriceSyncService}.
 *
 * <p>{@code idItem} joins {@code game_item.uex_item_id} and {@code idTerminal} joins {@code
 * terminal.id_terminal}; {@code priceBuy} / {@code priceSell} are in credits and {@code
 * dateModified} is a unix timestamp in seconds, each possibly {@code null}.
 */
@Builder
public record UexItemPriceDto(
    @JsonProperty("id_item") Integer idItem,
    @JsonProperty("id_terminal") Integer idTerminal,
    @JsonProperty("price_buy") Double priceBuy,
    @JsonProperty("price_sell") Double priceSell,
    @JsonProperty("date_modified") Long dateModified) {}
