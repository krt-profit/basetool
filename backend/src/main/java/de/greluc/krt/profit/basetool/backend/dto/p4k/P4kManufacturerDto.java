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

package de.greluc.krt.profit.basetool.backend.dto.p4k;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One manufacturer record from the P4K catalog, matched to {@code manufacturer} by {@link #guid}
 * ({@code scwiki_uuid}) first, then {@link #code} ({@code abbreviation}), then {@link #name}.
 *
 * <p>Any field may be {@code null} when the source did not resolve it.
 *
 * @param guid DataForge {@code __ref} GUID (string form of {@code manufacturer.scwiki_uuid})
 * @param code short manufacturer code (e.g. {@code "UPS"}); matches {@code
 *     manufacturer.abbreviation}
 * @param name English display name (e.g. {@code "Upsiders"}); matches {@code manufacturer.name}
 * @param nameDe German display name, or {@code null}
 * @param desc English description, filled into {@code manufacturer.description} when null
 * @param descDe German description, or {@code null}
 * @param nameKey raw {@code @LOC} name localization key (forensic; not persisted)
 * @param descKey raw {@code @LOC} description localization key (forensic; not persisted)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record P4kManufacturerDto(
    String guid,
    String code,
    String name,
    String nameDe,
    String desc,
    String descDe,
    String nameKey,
    String descKey) {}
