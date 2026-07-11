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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.util.UUID;

/**
 * Frontend mirror of the backend Squadron DTO. {@code isPromotionEnabled} is the per-squadron
 * feature flag for the promotion subsystem; the admin toggle lives at {@code
 * /api/proxy/squadrons/{id}/promotion-enabled} and the page-render flow reads the flag through
 * {@code CapabilityFlagsAdvice} so the sidebar and {@link
 * de.greluc.krt.profit.basetool.frontend.controller.PromotionPageController} know whether to expose
 * the promotion menu for a non-admin caller.
 *
 * @param id squadron identifier
 * @param name display name
 * @param shorthand short tag
 * @param description free-form text
 * @param active soft-delete flag
 * @param isPromotionEnabled per-squadron promotion-feature flag
 * @param isProfitEligible per-squadron Job-Order processor eligibility flag; the admin toggle lives
 *     at {@code /api/proxy/squadrons/{id}/profit-eligible} and decides whether the squadron appears
 *     in the Job-Order responsible (processing) picker
 * @param version optimistic-lock counter
 */
public record SquadronDto(
    UUID id,
    String name,
    String shorthand,
    String description,
    Boolean active,
    Boolean isPromotionEnabled,
    Boolean isProfitEligible,
    Long version) {}
