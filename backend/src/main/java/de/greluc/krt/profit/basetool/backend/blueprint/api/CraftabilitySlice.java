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

package de.greluc.krt.profit.basetool.backend.blueprint.api;

import java.util.UUID;

/**
 * One pooled amount the blueprint craftability counts: a member's SCU of one material at one
 * quality tier, summed across locations.
 *
 * @param materialId the material
 * @param quality the quality tier (0..1000)
 * @param totalScu the summed amount in SCU
 */
public record CraftabilitySlice(UUID materialId, Integer quality, Double totalScu) {}
