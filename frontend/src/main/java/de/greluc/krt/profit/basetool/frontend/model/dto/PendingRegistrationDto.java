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

import java.time.Instant;
import java.util.UUID;

/**
 * One row in the admin registration-approval queue (epic #720, Track 1).
 *
 * @param id the pending user's id
 * @param username the user's effective display name
 * @param serverNickname the user's per-guild Discord server nickname (REQ-DATA-008), or {@code
 *     null} when none was captured
 * @param registeredAt when the registration first appeared
 * @param decidedAt when an admin last decided this registration, i.e. the rejection time for a row
 *     in the rejected list (REQ-SEC-034); {@code null} for a row awaiting a decision
 * @param callsignCollision whether another account already holds this callsign — approving the row
 *     then creates a <b>second</b> account for it rather than admitting a new member (#1639)
 * @param version optimistic-lock version, echoed back on approve/reject
 */
public record PendingRegistrationDto(
    UUID id,
    String username,
    String serverNickname,
    Instant registeredAt,
    Instant decidedAt,
    boolean callsignCollision,
    Long version) {}
