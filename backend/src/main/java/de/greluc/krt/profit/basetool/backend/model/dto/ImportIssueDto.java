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
 * One review finding on a refinery screenshot import draft.
 *
 * <p>{@code field} is {@code "goods[i].sub"} for a draft row, {@code "goods[rowIndex]"} for a row
 * not added to the draft, or a plain field name for order-level issues.
 *
 * @param field dotted path to the affected field; never {@code null}
 * @param rawValue the verbatim screen read or a compact diagnostic
 * @param code machine-readable reason; the frontend translates it
 * @param severity visual grading (danger / warning / info)
 * @param confidence confidence in {@code [0,1]}, or {@code null} for order-level issues
 * @param suggestions ranked candidates for the material-match codes; {@code null} otherwise
 */
public record ImportIssueDto(
    String field,
    String rawValue,
    ImportIssueCode code,
    ImportIssueSeverity severity,
    Double confidence,
    List<ImportSuggestionDto> suggestions) {}
