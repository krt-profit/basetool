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

package de.greluc.krt.profit.basetool.frontend.catalogue.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Data transfer record carrying Material Price payload.
 *
 * @param id the price row
 * @param terminalName the terminal trading the material
 * @param priceBuy the price the terminal charges per SCU
 * @param priceSell the price the terminal pays per SCU
 * @param scuBuy the SCU the terminal has for sale
 * @param scuSell the SCU the terminal buys
 * @param statusBuy whether the terminal currently sells the material
 * @param statusSell whether the terminal currently buys the material
 * @param priceBuyAvg UEX's average purchase price per SCU, or {@code null}
 * @param priceSellAvg UEX's average sale price per SCU, or {@code null}
 * @param scuBuyAvg UEX's average SCU on sale, or {@code null}
 * @param scuSellAvg UEX's average SCU the terminal buys, or {@code null}
 * @param scuSellStockAvg UEX's average SCU of the terminal's sell stock, or {@code null}
 * @param containerSizes SCU container sizes the terminal handles for the material, or {@code null}
 *     when UEX lists none
 * @param uexUpdatedAt when UEX last updated this terminal's price, or {@code null} when unknown
 */
@DtoMirror
public record MaterialPriceDto(
    UUID id,
    String terminalName,
    BigDecimal priceBuy,
    BigDecimal priceSell,
    Long scuBuy,
    Long scuSell,
    Boolean statusBuy,
    Boolean statusSell,
    @Nullable BigDecimal priceBuyAvg,
    @Nullable BigDecimal priceSellAvg,
    @Nullable Long scuBuyAvg,
    @Nullable Long scuSellAvg,
    @Nullable Long scuSellStockAvg,
    @Nullable List<Integer> containerSizes,
    @Nullable Instant uexUpdatedAt) {}
