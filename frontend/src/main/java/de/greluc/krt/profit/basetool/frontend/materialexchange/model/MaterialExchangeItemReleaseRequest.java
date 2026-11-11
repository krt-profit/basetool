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

package de.greluc.krt.profit.basetool.frontend.materialexchange.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Outbound mirror of the backend {@code MaterialExchangeItemReleaseRequest}: lists an item on the
 * Materialbörse, named by exactly one of its blueprint product and its catalogue row (ADR-0371).
 *
 * @param productKey the normalized blueprint product key, or {@code null}
 * @param gameItemId the catalogue row of an item no blueprint produces, or {@code null}
 * @param quantity the offered whole-piece quantity
 * @param remark the trade remark, or {@code null}
 */
@DtoMirror
public record MaterialExchangeItemReleaseRequest(
    @Nullable String productKey, @Nullable UUID gameItemId, Integer quantity, String remark) {}
