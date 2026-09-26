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
 * One ship record from the P4K catalog, matched to {@code ship_type} by {@link #guid} ({@code
 * external_uuid}) first, then a unique case-insensitive {@link #className}, then a case-insensitive
 * {@link #name}.
 *
 * <p>Any field may be {@code null}. Crew and SCU columns are never touched.
 *
 * @param guid DataForge {@code __ref} GUID (string form of {@code ship_type.external_uuid})
 * @param className RSI engine class name; matches {@code ship_type.class_name}
 * @param path source DCB record path (forensic; not persisted)
 * @param name English display name; matches {@code ship_type.name}
 * @param nameDe German display name, or {@code null}
 * @param desc English description, filled into {@code ship_type.description_en} when null
 * @param descDe German description, filled into {@code ship_type.description_de} when null
 * @param nameKey raw {@code @LOC} name localization key (forensic; not persisted)
 * @param descKey raw {@code @LOC} description localization key (forensic; not persisted)
 * @param role ship role token (forensic; not persisted)
 * @param career ship career token (forensic; not persisted)
 * @param crewSize crew size (forensic; not persisted)
 * @param manufacturerGuid manufacturer GUID, filled into {@code ship_type.manufacturer} when null
 * @param vehicleDefinition vehicle-definition path (forensic; not persisted)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record P4kShipDto(
    String guid,
    String className,
    String path,
    String name,
    String nameDe,
    String desc,
    String descDe,
    String nameKey,
    String descKey,
    String role,
    String career,
    Integer crewSize,
    String manufacturerGuid,
    String vehicleDefinition) {}
