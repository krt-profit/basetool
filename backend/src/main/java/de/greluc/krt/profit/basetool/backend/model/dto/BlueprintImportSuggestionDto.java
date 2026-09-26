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
 * One fuzzy-match candidate offered for an unmatched imported blueprint name.
 *
 * @param productKey normalized product key of the candidate (echoed back on apply)
 * @param productName display spelling of the candidate product
 * @param score similarity to the external name in {@code [0.0, 1.0]} (1.0 = identical normalized)
 */
public record BlueprintImportSuggestionDto(String productKey, String productName, double score) {}
