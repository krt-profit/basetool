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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.util.List;

/**
 * Frontend mirror of the backend {@code RefineryImportDraftDto}: the non-persisted pre-fill
 * returned by {@code POST /api/v1/refinery-orders/import-extract}, rendered into the create form
 * with its review issues.
 *
 * @param order best-effort pre-fill; unmatched fields are {@code null}
 * @param issues every review finding, order-level first, then per row
 * @param goodsMatched number of draft rows whose input material was resolved
 * @param goodsTotal number of material rows read from the screenshots (incl. skipped)
 * @param rowsSkipped number of rows not added to the draft (refine-off, zero-qty, un-quoted)
 */
public record RefineryImportDraftDto(
    RefineryOrderDto order,
    List<ImportIssueDto> issues,
    int goodsMatched,
    int goodsTotal,
    int rowsSkipped) {}
