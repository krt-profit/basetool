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

/**
 * Boundary DTO for one commodity recovered when the crafted item is dismantled (the {@code
 * blueprint_dismantle_return} row). {@link #name} is the Wiki name snapshot.
 *
 * @param name display name of the returned commodity (Wiki snapshot)
 * @param quantityScu SCU amount recovered
 */
public record BlueprintDismantleReturnDto(String name, Double quantityScu) {}
