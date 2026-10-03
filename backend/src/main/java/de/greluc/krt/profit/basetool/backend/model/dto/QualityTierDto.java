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

import java.util.UUID;

/**
 * One quality tier of the catalogue (REQ-ORDERS-036).
 *
 * @param id the tier's primary key
 * @param code the stable upper-case code, e.g. {@code NONE} or {@code GOOD}
 * @param minQuality the floor a stock row must reach, 0 to 1000
 * @param labelDe the German display label
 * @param labelEn the English display label
 * @param sortOrder the picker position, ascending
 * @param active whether the tier is offered for new requirements
 * @param version optimistic-lock version
 */
public record QualityTierDto(
    UUID id,
    String code,
    int minQuality,
    String labelDe,
    String labelEn,
    int sortOrder,
    boolean active,
    Long version) {}
