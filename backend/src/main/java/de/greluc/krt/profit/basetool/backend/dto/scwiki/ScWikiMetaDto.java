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
 * Pagination metadata ({@code meta}) of a paginated SC Wiki response, read by {@code
 * ScWikiClient.fetchAllPages} to stop the page walk (ADR-0147).
 *
 * <p>A missing {@link #lastPage()} on a full first page is a contract break, and a {@link #total()}
 * above the distinct rows enumerated voids the census before any tombstone sweep.
 *
 * @param currentPage 1-based page number of this response
 * @param lastPage highest page number for the current filter / sort
 * @param perPage rows per page, as requested via {@code ?page[size]=…}
 * @param total total row count across all pages; used for the completeness check
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScWikiMetaDto(
    @JsonProperty("current_page") Integer currentPage,
    @JsonProperty("last_page") Integer lastPage,
    @JsonProperty("per_page") Integer perPage,
    @JsonProperty("total") Integer total) {}
