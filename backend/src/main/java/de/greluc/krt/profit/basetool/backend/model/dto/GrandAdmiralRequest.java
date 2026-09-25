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
 * Payload for designating an Organisationsleitung's Grand Admiral (REQ-ORG-021): exactly one of an
 * account or a free-text name.
 *
 * @param userId the account to designate, or {@code null} for a free-text holder
 * @param displayName the free-text holder name, or {@code null} when designating an account; at
 *     most 120 chars
 */
public record GrandAdmiralRequest(UUID userId, @Size(max = 120) String displayName) {}
