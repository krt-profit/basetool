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

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;

/**
 * Frontend mirror of the backend {@code RiskClassDto}; must match it field for field.
 *
 * @param code the class
 * @param maxTotal the highest deal total
 * @param maxAdvancePercent the advance share in percent
 * @param requiresKnownPartner whether the partner must have a settled deal
 * @param escortSuggested whether an escort is suggested
 * @param version the optimistic-locking version
 */
@DtoMirror
public record RiskClassDto(
    @BackendEnumAsString String code,
    long maxTotal,
    int maxAdvancePercent,
    boolean requiresKnownPartner,
    boolean escortSuggested,
    Long version) {}
