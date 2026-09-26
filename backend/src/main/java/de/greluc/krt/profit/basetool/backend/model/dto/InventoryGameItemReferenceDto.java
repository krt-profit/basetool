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
 * Slim {@code GameItem} reference for the Lager item-stock surfaces and the item-catalog picker
 * (REQ-INV-029).
 *
 * @param id the game item's primary key
 * @param name the item's display name
 * @param manufacturer the manufacturer's display name, or {@code null} when unresolved
 * @param kind the {@code GameItemKind} name, e.g. {@code WEAPON}
 */
public record InventoryGameItemReferenceDto(
    UUID id, String name, String manufacturer, String kind) {}
