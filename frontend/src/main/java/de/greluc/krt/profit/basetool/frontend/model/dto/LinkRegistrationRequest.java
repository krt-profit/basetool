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
import org.jetbrains.annotations.Nullable;

/**
 * Body of a link-registration request relayed to the backend (REQ-SEC-026): link a pending Discord
 * registration onto an existing account.
 *
 * @param targetUserId the existing account the Discord registration is linked into
 * @param version the pending registration's optimistic-lock version the admin last read; {@code
 *     null} bypasses the check
 */
public record LinkRegistrationRequest(@Nullable UUID targetUserId, @Nullable Long version) {}
