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

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A shop whose known item list names an item, shown to members as "available at" (REQ-P4K-044). The
 * place names are game texts of the starmap and stay with members (REQ-P4K-033).
 *
 * @param shopId the shop
 * @param terminalName the name of the linked UEX terminal, or {@code null} while unlinked
 * @param registryName the registry's shop name, or {@code null}
 * @param entityName the name of the shop entity, or {@code null}
 * @param starSystem the star system, e.g. {@code stanton}, or {@code null}
 * @param placeName the English name of the shop's starmap object, or {@code null}
 * @param placeNameDe the German name of the shop's starmap object, or {@code null}
 */
public record ItemShopAvailabilityDto(
    @NotNull UUID shopId,
    @Nullable String terminalName,
    @Nullable String registryName,
    @Nullable String entityName,
    @Nullable String starSystem,
    @Nullable String placeName,
    @Nullable String placeNameDe) {}
