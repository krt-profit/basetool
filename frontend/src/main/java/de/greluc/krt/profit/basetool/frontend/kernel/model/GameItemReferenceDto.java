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

package de.greluc.krt.profit.basetool.frontend.kernel.model;

import java.util.UUID;

/**
 * Frontend mirror of the backend {@code GameItemReferenceDto}: a slim reference to an orderable
 * finished item (id, name, and the {@code GameItemKind} name as a string).
 *
 * @param id the game item's id
 * @param name the item's display name
 * @param kind the item kind name (e.g. {@code WEAPON})
 */
@DtoMirror
public record GameItemReferenceDto(UUID id, String name, String kind) {}
