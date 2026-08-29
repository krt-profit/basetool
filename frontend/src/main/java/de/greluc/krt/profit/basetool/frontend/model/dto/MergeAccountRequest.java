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
 * registration the member can log into now.
 *
 * @param sourceUserId the older account to empty -- the one whose callsign collided
 * @param version the registration's optimistic-lock version the admin last read; {@code null}
 *     bypasses the check
 */
public record MergeAccountRequest(@Nullable UUID sourceUserId, @Nullable Long version) {}
