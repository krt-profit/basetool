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

import de.greluc.krt.profit.basetool.backend.model.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.model.OrgRelativeRole;
import de.greluc.krt.profit.basetool.backend.model.SelectorKind;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Write payload for one recipient selector. Which of {@code userId} / {@code roleCode} / ({@code
 * orgRelativeRole} + {@code contextRole}) must be set follows from {@link #kind}; the service
 * validates the combination.
 *
 * @param kind how the selector resolves recipients (required)
 * @param userId target user for {@code SPECIFIC_USER}
 * @param roleCode role code for {@code ROLE}
 * @param orgRelativeRole org-relative role for {@code ORG_RELATIVE_ROLE}
 * @param contextRole context org unit for {@code ORG_RELATIVE_ROLE}
 */
public record NotificationRuleSelectorWriteRequest(
    @NotNull SelectorKind kind,
    UUID userId,
    String roleCode,
    OrgRelativeRole orgRelativeRole,
    NotificationContextRole contextRole) {}
