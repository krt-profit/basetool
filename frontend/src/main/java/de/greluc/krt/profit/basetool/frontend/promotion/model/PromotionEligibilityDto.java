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

package de.greluc.krt.profit.basetool.frontend.promotion.model;

import de.greluc.krt.profit.basetool.frontend.model.DtoMirror;
import java.util.List;

/**
 * Frontend mirror of the backend's {@code PromotionEligibilityResponse}.
 *
 * @param userId the evaluated member's JWT-sub identifier
 * @param fromRank the rank the member currently holds
 * @param toRank the rank the member would be promoted to
 * @param eligible {@code true} iff every entry in {@code checks} is satisfied
 * @param hasConfiguredRules {@code true} iff at least one requirement is configured for this
 *     transition
 * @param checks per-requirement evaluation, in stable display order
 */
@DtoMirror
public record PromotionEligibilityDto(
    String userId,
    int fromRank,
    int toRank,
    boolean eligible,
    boolean hasConfiguredRules,
    List<PromotionRequirementCheckDto> checks) {}
