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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import java.util.UUID;

/**
 * Slim reference projection of a {@code Blueprint} for item-order payloads and the blueprint
 * picker. Identifies the recipe chosen to produce an ordered item without exposing the full
 * ingredient graph.
 *
 * @param id the blueprint's primary key
 * @param outputName the human-readable name of the produced item as recorded on the blueprint
 * @param scwikiKey the SC-Wiki key of the blueprint (stable identifier for disambiguation)
 */
public record BlueprintReferenceDto(UUID id, String outputName, String scwikiKey) {}
