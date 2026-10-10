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
 * One installation of a connected client (REQ-XCH-007, REQ-XCH-032).
 *
 * @param id the installation id
 * @param label the client's label for it, or {@code null}
 * @param firstSeenAt when it was first seen
 * @param lastSeenAt when it was last seen
 * @param unseen whether the member has not yet seen the notification announcing it
 */
public record ConnectedInstallationDto(
    UUID id, String label, Instant firstSeenAt, Instant lastSeenAt, boolean unseen) {}
