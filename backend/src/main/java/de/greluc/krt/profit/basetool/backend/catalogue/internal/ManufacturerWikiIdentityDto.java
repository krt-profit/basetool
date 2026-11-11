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

import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One SC Wiki entry of a manufacturer the Wiki lists more than once.
 *
 * @param scwikiUuid the Wiki manufacturer UUID
 * @param wikiName the Wiki's name for the entry
 * @param wikiCode the Wiki's engine code, or {@code null} when the Wiki sends none
 * @param primary whether this entry is the manufacturer's primary Wiki link
 * @param syncedAt when the Wiki manufacturer sync last saw the entry
 */
public record ManufacturerWikiIdentityDto(
    UUID scwikiUuid,
    String wikiName,
    @Nullable String wikiCode,
    boolean primary,
    Instant syncedAt) {}
