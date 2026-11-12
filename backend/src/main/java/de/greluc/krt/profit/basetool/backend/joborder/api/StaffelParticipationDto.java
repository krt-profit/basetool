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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipOptionDto;
import java.util.UUID;

/**
 * A Staffel's retained relationship to the order.
 *
 * @param id the durable participation identity
 * @param unit the participating Staffel
 * @param version the observed participation version
 * @param active whether this Staffel is currently supporting the order
 * @param canWithdraw whether the caller may end the active support
 */
public record StaffelParticipationDto(
    UUID id, OrgUnitMembershipOptionDto unit, Long version, boolean active, boolean canWithdraw) {}
