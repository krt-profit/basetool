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
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Builder;
import org.jetbrains.annotations.Nullable;

/**
 * Inbound JSON record for UEX Corp's commodity-price endpoint. Mapped onto the project's {@code
 * MaterialPrice} rows by {@code UexCommodityService}; downstream code consumes the entity, not this
 * DTO.
 */
@Builder
public record UexCommodityPriceDto(
    @JsonProperty("id_commodity") Integer idCommodity,
    @JsonProperty("commodity_name") String commodityName,
    @JsonProperty("id_terminal") Integer idTerminal,
    @JsonProperty("terminal_name") String terminalName,
    @JsonProperty("price_buy") BigDecimal priceBuy,
    @JsonProperty("price_sell") BigDecimal priceSell,
    @JsonProperty("scu_buy") Integer scuBuy,
    @JsonProperty("scu_sell") Integer scuSell,
    @JsonProperty("scu_sell_stock") Integer scuSellStock,
    @JsonProperty("status_buy") Integer statusBuy,
    @JsonProperty("status_sell") Integer statusSell,
    @JsonProperty("date_modified") Long dateModified) {
  @Nullable
  public Instant getParsedDateModified() {
    return dateModified != null ? Instant.ofEpochSecond(dateModified) : null;
  }

  public Boolean isStatusBuy() {
    return statusBuy != null && statusBuy == 1;
  }

  public Boolean isStatusSell() {
    return statusSell != null && statusSell == 1;
  }
}
