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
 * One output name a P4K APPLY replaced on an active recipe, with what keys the recipe's product
 * (REQ-P4K-079); a JPQL constructor projection, not a controller-boundary DTO.
 *
 * @param producedItemGuid the produced item's DataForge GUID, the product key when present, or
 *     {@code null}
 * @param outputName the recipe's current output name
 * @param formerName the output name the APPLY replaced
 */
public record BlueprintFormerNameRow(
    @Nullable UUID producedItemGuid, @NotNull String outputName, @NotNull String formerName) {}
