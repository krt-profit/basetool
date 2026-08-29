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
 * Frontend mirror of the backend {@code NotificationRuleSelectorWriteRequest}. Enum-typed fields
 * are sent as {@link String}; field names match the backend record (mirror-DTO rule).
 *
 * @param kind selector kind
 * @param userId target user for {@code SPECIFIC_USER}
 * @param roleCode role code for {@code ROLE}
 * @param orgRelativeRole org-relative role for {@code ORG_RELATIVE_ROLE}
 * @param contextRole context org unit for {@code ORG_RELATIVE_ROLE}
 */
public record NotificationRuleSelectorWriteRequest(
    @BackendEnumAsString String kind,
    UUID userId,
    String roleCode,
    @BackendEnumAsString String orgRelativeRole,
    @BackendEnumAsString String contextRole) {}
