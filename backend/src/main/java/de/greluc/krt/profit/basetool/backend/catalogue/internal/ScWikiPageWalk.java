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

import java.util.List;

/**
 * Rows and census verdict of one {@link ScWikiClient} page walk, before they are reported to the
 * run.
 *
 * @param <T> row type
 * @param rows decoded rows across the fetched pages
 * @param validRows distinct rows with a usable UUID
 * @param complete whether the walk enumerated the whole feed
 * @param shifted whether every page was read but the feed re-paginated or grew during the walk
 */
record ScWikiPageWalk<T>(List<T> rows, long validRows, boolean complete, boolean shifted) {}
