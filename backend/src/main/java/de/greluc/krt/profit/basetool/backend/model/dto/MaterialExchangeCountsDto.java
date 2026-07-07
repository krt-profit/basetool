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

package de.greluc.krt.profit.basetool.backend.model.dto;

/**
 * The Materialbörse tab counts — the total number of active offers on the whole board and the
 * caller's own active offers. Drives the "Alle Angebote" / "Meine Angebote" tab counters, which are
 * unaffected by the search/quality/amount filters (they are board totals).
 *
 * @param all the number of active offers across the whole board.
 * @param mine the number of the caller's own active offers.
 */
public record MaterialExchangeCountsDto(long all, long mine) {}
