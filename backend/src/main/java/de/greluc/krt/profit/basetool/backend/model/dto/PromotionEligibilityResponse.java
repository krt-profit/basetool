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
 * Result of evaluating all configured rank requirements for a single rank transition (e.g. 20
 * &rarr; 19) against one member's evaluations.
 *
 * <p>{@link #eligible} is {@code true} iff every entry in {@link #checks} is satisfied. If no
 * requirement is configured for the transition the record is returned with an empty {@code checks}
 * list and {@code eligible == false} so that the UI can clearly mark "no rules defined" instead of
 * silently showing the user as eligible.
 *
 * @param userId the JWT-sub identifier of the evaluated member
 * @param fromRank the rank the member currently holds
 * @param toRank the rank the member would be promoted to
 * @param eligible {@code true} iff every check passes <em>and</em> at least one rule exists
 * @param hasConfiguredRules {@code true} iff at least one requirement is configured for this
 *     transition
 * @param checks per-requirement evaluation, in stable display order
 */
public record PromotionEligibilityResponse(
    UUID userId,
    int fromRank,
    int toRank,
    boolean eligible,
    boolean hasConfiguredRules,
    List<PromotionRequirementCheckResponse> checks) {}
