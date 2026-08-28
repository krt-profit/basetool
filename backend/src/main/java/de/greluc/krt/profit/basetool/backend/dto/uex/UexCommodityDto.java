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
 * Inbound JSON record for UEX Corp's <code>/commodities</code> catalogue endpoint. Mapped to the
 * project's own {@code Material} entity by {@code UexCommodityService}; downstream code consumes
 * the entity, not this DTO.
 */
@Builder
public record UexCommodityDto(
    @JsonProperty("id") Integer id,
    @JsonProperty("name") String name,
    @JsonProperty("code") String code,
    @JsonProperty("kind") String kind,
    @JsonProperty("weight_scu") Double weightScu,
    @JsonProperty("price_buy") Double priceBuy,
    @JsonProperty("price_sell") Double priceSell,
    @JsonProperty("is_available") Integer isAvailable,
    @JsonProperty("is_available_live") Integer isAvailableLive,
    @JsonProperty("is_extractable") Integer isExtractable,
    @JsonProperty("is_mineral") Integer isMineral,
    @JsonProperty("is_raw") Integer isRaw,
    @JsonProperty("is_pure") Integer isPure,
    @JsonProperty("is_refinable") Integer isRefinable,
    @JsonProperty("is_refined") Integer isRefined,
    @JsonProperty("is_harvestable") Integer isHarvestable,
    @JsonProperty("is_buyable") Integer isBuyable,
    @JsonProperty("is_sellable") Integer isSellable,
    @JsonProperty("is_temporary") Integer isTemporary,
    @JsonProperty("is_illegal") Integer isIllegal,
    @JsonProperty("is_volatile_qt") Integer isVolatileQt,
    @JsonProperty("is_volatile_time") Integer isVolatileTime,
    @JsonProperty("is_inert") Integer isInert,
    @JsonProperty("is_explosive") Integer isExplosive,
    @JsonProperty("is_buggy") Integer isBuggy,
    @JsonProperty("is_fuel") Integer isFuel) {}
