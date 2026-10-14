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

package de.greluc.krt.profit.basetool.frontend.purchasing.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code ReportMaterialDto}; must match it field for field.
 *
 * @param catalogueId the material or item
 * @param name its name
 * @param dealCount deals for it
 * @param boughtAmount the amount accepted
 * @param averageUnitPrice the weighted unit price
 * @param uexReferencePrice the UEX buy price, or {@code null}
 */
@DtoMirror
public record ReportMaterialDto(
    UUID catalogueId,
    String name,
    int dealCount,
    double boughtAmount,
    double averageUnitPrice,
    Double uexReferencePrice) {}
