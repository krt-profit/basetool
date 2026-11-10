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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Chooses which SC Wiki entry of a manufacturer is its primary link.
 *
 * @param scwikiUuid the Wiki entry that becomes primary; must be one of the manufacturer's entries
 * @param version the manufacturer {@code @Version} the admin last saw
 */
public record ManufacturerWikiPrimaryRequest(@NotNull UUID scwikiUuid, @NotNull Long version) {}
