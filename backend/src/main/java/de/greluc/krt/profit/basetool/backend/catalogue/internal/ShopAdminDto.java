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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShopKind;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A P4K shop as the admin list shows it, with its place and its UEX terminal link (REQ-P4K-042,
 * REQ-P4K-043).
 *
 * @param id the shop
 * @param entityName the name of the shop entity
 * @param registryName the registry's shop name, or {@code null}
 * @param starSystem the star system, or {@code null}
 * @param kind the kind, or {@code null}
 * @param brand the brand, or {@code null}
 * @param commodityExchange whether the shop is a commodity exchange
 * @param itemListKnown whether the shop's item list is known
 * @param placeName the English name of the shop's starmap object, or {@code null}
 * @param terminalId the linked UEX terminal, or {@code null}
 * @param terminalName the linked terminal's name, or {@code null}
 * @param manual whether an admin set or cleared the link
 * @param retired whether the sweep stamped the shop or an admin hid it
 */
public record ShopAdminDto(
    @NotNull UUID id,
    @Nullable String entityName,
    @Nullable String registryName,
    @Nullable String starSystem,
    @Nullable P4kShopKind kind,
    @Nullable String brand,
    boolean commodityExchange,
    boolean itemListKnown,
    @Nullable String placeName,
    @Nullable UUID terminalId,
    @Nullable String terminalName,
    boolean manual,
    boolean retired) {}
