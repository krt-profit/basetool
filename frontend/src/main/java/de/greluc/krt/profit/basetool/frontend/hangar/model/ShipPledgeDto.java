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

package de.greluc.krt.profit.basetool.frontend.hangar.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Pledge data of one ship from a hangar import, sent only to the owner and to admins
 * (REQ-HANGAR-015).
 *
 * @param pledgeId the RSI pledge id
 * @param pledgeDate the purchase date, or {@code null}
 * @param pledgeCost the price of the whole pledge, or {@code null}
 * @param pledgeCostCurrency the ISO currency code of the price, or {@code null}
 * @param meltable whether the pledge can be melted, or {@code null} when unknown
 * @param giftable whether the pledge can be gifted, or {@code null} when unknown
 */
@DtoMirror
public record ShipPledgeDto(
    String pledgeId,
    LocalDate pledgeDate,
    BigDecimal pledgeCost,
    String pledgeCostCurrency,
    Boolean meltable,
    Boolean giftable) {}
