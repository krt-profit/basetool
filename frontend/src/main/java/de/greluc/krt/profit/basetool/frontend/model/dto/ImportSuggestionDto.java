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

import java.util.UUID;

/**
 * Frontend mirror of the backend {@code ImportSuggestionDto}: one ranked candidate material for an
 * unmatched or fuzzily matched screen name, rendered as a pick chip.
 *
 * @param id id of the candidate material (applied to the row's material select on pick)
 * @param name display name of the candidate, e.g. {@code "Stileron (Raw)"}
 * @param score similarity to the raw screen name in {@code [0.0, 1.0]}
 */
public record ImportSuggestionDto(UUID id, String name, double score) {}
