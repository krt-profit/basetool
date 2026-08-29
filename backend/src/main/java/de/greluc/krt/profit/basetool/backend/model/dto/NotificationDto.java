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
import java.util.Map;
import java.util.UUID;

/**
 * Read DTO for a single notification. The {@code recipientUserId} is intentionally NOT exposed —
 * callers only ever see their own notifications via the JWT-scoped endpoints.
 *
 * @param id notification id
 * @param type machine type name; the frontend resolves it to {@code notifications.type.<type>}
 * @param params i18n render parameters (empty when the type needs none)
 * @param entityType loose type tag of the originating aggregate for deep-linking, or {@code null}
 * @param entityId loose id of the originating aggregate for deep-linking, or {@code null}
 * @param read whether the recipient has marked it read
 * @param readAt when it was marked read, or {@code null} while unread
 * @param version optimistic-lock version
 * @param createdAt creation timestamp
 * @param updatedAt last-modification timestamp
 */
public record NotificationDto(
    UUID id,
    String type,
    Map<String, String> params,
    String entityType,
    UUID entityId,
    boolean read,
    Instant readAt,
    Long version,
    Instant createdAt,
    Instant updatedAt) {}
