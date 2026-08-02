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
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Pagination metadata returned by every paginated SC Wiki endpoint. Lives inside the {@code meta}
 * field of {@link ScWikiResponseDto}; consumed by {@code ScWikiClient.fetchAllPages} to decide when
 * to stop walking pages.
 *
 * <p>The standard envelope shape (verified against the live API on 2026-05-27) is:
 *
 * <pre>{@code
 * {
 *   "data": [ ... ],
 *   "links": { ... },
 *   "meta": {
 *     "current_page": 1,
 *     "last_page":    2,
 *     "per_page":    200,
 *     "total":       205
 *   }
 * }
 * }</pre>
 *
 * <p>Some sub-resource endpoints (e.g. {@code /api/blueprints/{uuid}}) return a single row without
 * an envelope; this DTO is not used for those.
 *
 * <p>Because the record is {@link JsonIgnoreProperties}{@code (ignoreUnknown = true)}, an upstream
 * rename of any of these fields decodes as {@code null} instead of failing — which is why the
 * client treats an absent {@link #lastPage()} on a full first page as a contract break, and
 * cross-checks {@link #total()} against the merged row count before letting the result drive a
 * tombstone sweep.
 *
 * @param currentPage 1-based page number this response represents
 * @param lastPage highest page number for the current filter / sort
 * @param perPage rows per page (= the {@code ?page[size]=…} we sent)
 * @param total total row count across all pages; the client's completeness cross-check reads it
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScWikiMetaDto(
    @JsonProperty("current_page") Integer currentPage,
    @JsonProperty("last_page") Integer lastPage,
    @JsonProperty("per_page") Integer perPage,
    @JsonProperty("total") Integer total) {}
