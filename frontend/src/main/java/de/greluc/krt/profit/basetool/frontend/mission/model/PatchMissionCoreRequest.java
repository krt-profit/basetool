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

import java.util.UUID;

/**
 * Write payload replacing a mission's core section.
 *
 * @param name the mission name
 * @param description the Markdown description, or {@code null}
 * @param calendarLink the https calendar link, or {@code null}
 * @param status the mission status, or {@code null}
 * @param operationId the linked operation, or {@code null}
 * @param version the core version the client read
 * @param meetingPoint the meeting point, or {@code null}
 */
public record PatchMissionCoreRequest(
    String name,
    String description,
    String calendarLink,
    String status,
    UUID operationId,
    Long version,
    String meetingPoint) {}
