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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One material or game item of a purchasing report.
 *
 * @param catalogueId the material or game item
 * @param name its name
 * @param dealCount deals for it
 * @param boughtAmount the amount accepted
 * @param averageUnitPrice the unit price weighted by the deal amounts, in aUEC
 * @param uexReferencePrice the UEX buy price of a material, or {@code null}
 */
public record ReportMaterialDto(
    UUID catalogueId,
    String name,
    int dealCount,
    double boughtAmount,
    double averageUnitPrice,
    @Nullable Double uexReferencePrice) {}
