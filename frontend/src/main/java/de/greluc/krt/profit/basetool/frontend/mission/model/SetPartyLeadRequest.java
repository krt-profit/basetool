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

package de.greluc.krt.profit.basetool.frontend.mission.model;

import de.greluc.krt.profit.basetool.frontend.model.DtoMirror;
import java.util.UUID;

/**
 * Write payload for assigning or clearing a mission's party lead; neither a user nor a name clears
 * it.
 *
 * @param userId the registered member to assign, or {@code null}
 * @param guestName the free-text handle to resolve, or {@code null}
 * @param version the party-lead version the client read
 */
@DtoMirror
public record SetPartyLeadRequest(UUID userId, String guestName, Long version) {}
