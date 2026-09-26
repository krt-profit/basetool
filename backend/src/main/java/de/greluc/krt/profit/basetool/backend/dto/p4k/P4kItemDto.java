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
import java.util.List;

/**
 * One item record from the P4K catalog, matched to {@code game_item} by {@link #guid} ({@code
 * external_uuid}) first, then a unique case-insensitive {@link #className}, then a unique
 * case-insensitive {@link #name}.
 *
 * <p>Any field may be {@code null} when the source did not resolve it.
 *
 * @param guid DataForge {@code __ref} GUID (string form of {@code game_item.external_uuid})
 * @param className RSI engine class name; matches {@code game_item.class_name}
 * @param path source DCB record path (forensic; not persisted)
 * @param type item type token (forensic; not persisted)
 * @param subType item sub-type token (forensic; not persisted)
 * @param size component size tier (forensic; not persisted)
 * @param grade item grade marker (forensic; not persisted)
 * @param mass mass in kg, filled into {@code game_item.mass} when null
 * @param manufacturerGuid manufacturer GUID, filled into {@code game_item.manufacturer} when null
 * @param tags raw item tag tokens (forensic; not persisted)
 * @param name English display name; matches {@code game_item.name}
 * @param nameDe German display name, or {@code null}
 * @param desc English description, filled into {@code game_item.description_en} when null
 * @param descDe German description, filled into {@code game_item.description_de} when null
 * @param nameKey raw {@code @LOC} name localization key (forensic; not persisted)
 * @param descKey raw {@code @LOC} description localization key (forensic; not persisted)
 * @param displayType {@code SCItemPurchasableParams.displayType} (forensic; not persisted)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record P4kItemDto(
    String guid,
    String className,
    String path,
    String type,
    String subType,
    Integer size,
    String grade,
    Double mass,
    String manufacturerGuid,
    List<String> tags,
    String name,
    String nameDe,
    String desc,
    String descDe,
    String nameKey,
    String descKey,
    String displayType) {}
