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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * A member's home-location choice through an exchange client, in the shape of {@code
 * patch-change.schema.json#/$defs/homeLocationChoice} (REQ-PATCH-052).
 *
 * @param location the id of one of the home locations the patch-change read offers
 */
public record ExchangeHomeLocationChoiceRequest(@NotNull UUID location) {}
