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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The terse answer to a client's create (REQ-OXCH-004), read from the stored order: a retry under
 * the create's key answers the order created first, with its current state and its stored target.
 *
 * @param id the order
 * @param status the order's current lifecycle status
 * @param versions the section versions a later write echoes
 * @param target the stored target in whole aUEC
 */
public record ExchangeJobOrderCreatedDto(
    @NotNull UUID id,
    @NotNull JobOrderStatus status,
    @NotNull ExchangeJobOrderDto.Versions versions,
    long target) {}
