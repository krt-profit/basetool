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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.time.Instant;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code JobOrderNumberHitDto}, backing the quick access's order
 * number search.
 *
 * @param id order primary key
 * @param displayId human-readable sequential id
 * @param status lifecycle status
 * @param createdAt creation timestamp
 */
@DtoMirror
public record JobOrderNumberHitDto(
    UUID id, Integer displayId, @BackendEnumAsString String status, Instant createdAt) {}
