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

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Who removed a feed entry (REQ-XCH-013); the client fields are left out for other channels.
 *
 * @param channel {@code web}, {@code app}, {@code client} or {@code system}
 * @param clientId the registry client id when a client removed it
 * @param installationId the removing installation's opaque id, when a client removed it and the
 *     installation is still known
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExchangeRemovedByDto(
    @NotNull String channel, @Nullable String clientId, @Nullable String installationId) {}
