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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import java.time.Instant;
import java.util.UUID;

/**
 * One installation of a client with writes a bulk undo could reach, for limiting it to that one;
 * the member-given label is left out (REQ-XCH-034).
 *
 * @param installationId the installation
 * @param memberName the member's display name
 * @param lastSeenAt when the installation was last seen
 * @param revoked whether it is disconnected
 * @param entries how many of its journal entries are not undone
 */
public record ExchangeBulkUndoInstallationDto(
    UUID installationId, String memberName, Instant lastSeenAt, boolean revoked, long entries) {}
