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
 * Frontend mirror of the backend {@code PurchasingTierRefDto}; must match it field for field.
 *
 * @param id the tier
 * @param code the stable code
 * @param labelDe the German label
 * @param labelEn the English label
 * @param minQuality the lowest quality that satisfies the tier
 */
@DtoMirror
public record PurchasingTierRefDto(
    UUID id, String code, String labelDe, String labelEn, int minQuality) {}
