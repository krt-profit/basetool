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

/**
 * Pagination links of a paginated SC Wiki response, bound only so the envelope parses; the client
 * builds its pages from {@link ScWikiMetaDto#lastPage()}.
 *
 * @param first URL of page 1 (always present)
 * @param last URL of the last page
 * @param prev URL of the previous page, or {@code null} on page 1
 * @param next URL of the next page, or {@code null} on the last page
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScWikiPaginationLinksDto(String first, String last, String prev, String next) {}
