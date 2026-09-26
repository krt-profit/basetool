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
import java.util.List;

/**
 * Envelope of a paginated SC Wiki response, with {@code T} the row type bound at the {@link
 * de.greluc.krt.profit.basetool.backend.integration.scwiki.ScWikiClient} call site.
 *
 * <p>Unlike the UEX envelope it has no {@code status} field; unknown fields are ignored.
 *
 * @param data the payload rows of the current page
 * @param meta pagination metadata
 * @param links pagination link URIs (not followed)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScWikiResponseDto<T>(
    List<T> data, ScWikiMetaDto meta, ScWikiPaginationLinksDto links) {}
