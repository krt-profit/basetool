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

import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Inbound payload for {@code PUT /api/v1/org-hierarchy/organisationsleitung/{id}/grand-admiral}
 * (REQ-ORG-021). A Grand Admiral is held by <b>either</b> a Basetool account <b>or</b> a free-text
 * name for a member without one — mutually exclusive, matching the chart's holder rule
 * (REQ-ORG-020). Supply exactly one: {@code userId} designates an account Grand Admiral (with
 * OL-member rights, appointed under Leitung), {@code displayName} sets a free-text one (grants
 * nothing, set in the chart editor). The controller branches on which is present; the service
 * rejects a blank {@code displayName}.
 *
 * @param userId the account to designate as Grand Admiral, or {@code null} for a free-text holder.
 * @param displayName the free-text holder name, or {@code null} when designating an account.
 *     Bounded to the shared 120-char holder-name length.
 */
public record GrandAdmiralRequest(UUID userId, @Size(max = 120) String displayName) {}
