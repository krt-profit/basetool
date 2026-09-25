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

package de.greluc.krt.profit.basetool.backend.model.dto;

import java.util.List;
import java.util.UUID;

/**
 * Result of evaluating all rank requirements of one rank transition against a member's evaluations.
 *
 * <p>With no requirement configured, {@code checks} is empty and {@code eligible} is {@code false}.
 *
 * @param userId the evaluated member's JWT subject
 * @param fromRank the member's current rank
 * @param toRank the target rank
 * @param eligible {@code true} iff at least one rule exists and every check passes
 * @param hasConfiguredRules {@code true} iff at least one requirement is configured
 * @param checks per-requirement results, in stable display order
 */
public record PromotionEligibilityResponse(
    UUID userId,
    int fromRank,
    int toRank,
    boolean eligible,
    boolean hasConfiguredRules,
    List<PromotionRequirementCheckResponse> checks) {}
