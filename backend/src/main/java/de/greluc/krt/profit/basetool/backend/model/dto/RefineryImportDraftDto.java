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

import java.util.List;

/**
 * Never-persisted pre-fill of the refinery create form built from a {@code RefineryExtract}.
 *
 * <p>Fields whose matching failed stay {@code null}, even where save-time validation requires them.
 *
 * @param order the pre-filled order, goods in on-screen order
 * @param issues every finding for the user, order-level first; empty on a clean match
 * @param goodsMatched number of rows whose input material was resolved
 * @param goodsTotal number of rows read, including skipped ones
 * @param rowsSkipped number of rows not added to the draft
 */
public record RefineryImportDraftDto(
    RefineryOrderDto order,
    List<ImportIssueDto> issues,
    int goodsMatched,
    int goodsTotal,
    int rowsSkipped) {}
