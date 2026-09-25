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

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the admin registration-approval queue; carries no Discord id.
 *
 * @param id the pending user's id
 * @param username the user's effective display name
 * @param serverNickname the Discord server nickname (REQ-DATA-018), or {@code null}
 * @param registeredAt when the registration first appeared
 * @param decidedAt the last decision time (REQ-SEC-034), or {@code null} while awaiting a decision
 * @param callsignCollision whether another account already holds this callsign, case-insensitively,
 *     so approving would create a second account
 * @param version optimistic-lock version, echoed back on approve or reject
 */
public record PendingRegistrationDto(
    UUID id,
    String username,
    String serverNickname,
    Instant registeredAt,
    Instant decidedAt,
    boolean callsignCollision,
    Long version) {}
