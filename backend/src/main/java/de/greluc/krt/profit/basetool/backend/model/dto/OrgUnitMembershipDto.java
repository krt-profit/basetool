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

import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import java.time.Instant;
import java.util.UUID;

/**
 * Wire shape for {@link de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership}, with the
 * composite key unpacked and a denormalized display name for the admin roster.
 *
 * <p>Flags are boxed to mirror {@link MembershipFlagsPatchRequest}, where {@code null} means "no
 * change".
 *
 * @param userId the member's user id; always populated
 * @param userDisplayName the user's display name, else the username
 * @param orgUnitId the org unit the user belongs to; always populated
 * @param kind the referenced org unit's {@link OrgUnitKind}
 * @param isLogistician whether the membership grants the Logistician role in the org unit
 * @param isMissionManager whether the membership grants the Mission Manager role in the org unit
 * @param isLead whether the membership holds the {@code SK_LEAD} rank; only on a Spezialkommando
 * @param joinedAt when the membership was granted, in UTC
 * @param version optimistic-lock version; required on patch requests
 */
public record OrgUnitMembershipDto(
    UUID userId,
    String userDisplayName,
    UUID orgUnitId,
    OrgUnitKind kind,
    Boolean isLogistician,
    Boolean isMissionManager,
    Boolean isLead,
    Instant joinedAt,
    Long version) {}
