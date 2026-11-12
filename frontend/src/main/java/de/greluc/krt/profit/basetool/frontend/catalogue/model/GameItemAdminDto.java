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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code GameItemAdminDto}: one catalogue game item with its
 * obtainability (REQ-P4K-048).
 *
 * @param id the item
 * @param name the display name; a game text
 * @param nameDe the German name, or {@code null}; a game text
 * @param type the item type, or {@code null}
 * @param subType the item sub-type, or {@code null}
 * @param manufacturerName the manufacturer's name, or {@code null}
 * @param retired whether the P4K swept the item or an admin hid it through a review case
 * @param unobtainableSince ISO-8601 instant an admin hid the item as not obtainable, or {@code
 *     null} while the pickers offer it
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@DtoMirror
public record GameItemAdminDto(
    UUID id,
    String name,
    String nameDe,
    String type,
    String subType,
    String manufacturerName,
    boolean retired,
    String unobtainableSince) {}
