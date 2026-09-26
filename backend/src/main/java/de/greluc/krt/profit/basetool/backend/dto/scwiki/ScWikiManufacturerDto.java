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

package de.greluc.krt.profit.basetool.backend.dto.scwiki;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * SC Wiki manufacturer row from {@code GET /api/manufacturers}, used by {@code
 * ScWikiManufacturerSyncService} to stamp {@code scwiki_uuid} and {@code scwiki_code} onto existing
 * manufacturer rows.
 *
 * @param uuid Wiki manufacturer UUID, written to {@code manufacturer.scwiki_uuid}
 * @param name manufacturer display name, matched case-insensitively
 * @param code short code, e.g. {@code "AEGS"}; written to {@code manufacturer.scwiki_code} and used
 *     as the abbreviation fallback match
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScWikiManufacturerDto(UUID uuid, String name, String code) implements ScWikiRow {}
