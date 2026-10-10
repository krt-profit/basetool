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

import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One notification type in the caller's preferences (REQ-NOTIF-027).
 *
 * @param type the notification type
 * @param mutable whether the member may mute it; {@code false} for the types that serve a legal
 *     deadline or a security signal
 * @param muted whether the caller has muted it; always {@code false} while {@code mutable} is
 *     {@code false}
 */
@Schema(description = "One notification type and whether the caller receives it.")
public record NotificationPreferenceDto(NotificationType type, boolean mutable, boolean muted) {}
