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

package de.greluc.krt.profit.basetool.frontend.catalogue.model;

import java.util.UUID;

/**
 * Frontend mirror of the backend frequency-type DTO, read by the admin mission-data page and sent
 * as its create and update body.
 *
 * @param id frequency-type identifier, {@code null} on create
 * @param name display name
 * @param description free-form text
 * @param active soft-delete flag
 * @param sortIndex position in the admin order, assigned by the backend
 * @param version optimistic-lock counter, {@code null} on create
 */
public record FrequencyTypeDto(
    UUID id, String name, String description, boolean active, Integer sortIndex, Long version) {}
