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
 * SC Wiki manufacturer DTO — one row of the {@code GET /api/manufacturers} list
 * (SC_WIKI_SYNC_PLAN.md §3.2 / §6.4), consumed by the R6 {@code ScWikiManufacturerSyncService} to
 * stamp {@code scwiki_uuid} / {@code scwiki_code} onto the manufacturer rows the UEX sync already
 * created. The payload is flat — {@code {uuid, name, code, link}} — and only the first three are
 * captured; {@code link} is ignored.
 *
 * <p>Distinct from {@link ScWikiItemManufacturerDto}, which carries the same three fields but is
 * the manufacturer reference <em>nested</em> inside an item payload; this record binds the
 * standalone manufacturer-catalogue endpoint.
 *
 * @param uuid Wiki manufacturer UUID (the cross-ref key written to {@code
 *     manufacturer.scwiki_uuid})
 * @param name manufacturer display name (used for the case-insensitive name match)
 * @param code manufacturer short code, e.g. {@code "AEGS"} (written to {@code manufacturer
 *     .scwiki_code}; also the abbreviation-fallback match key)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScWikiManufacturerDto(UUID uuid, String name, String code) implements ScWikiRow {}
