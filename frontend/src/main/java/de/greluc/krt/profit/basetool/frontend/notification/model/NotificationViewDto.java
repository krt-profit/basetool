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

package de.greluc.krt.profit.basetool.frontend.notification.model;

import de.greluc.krt.profit.basetool.frontend.model.DtoMirror;
import java.util.UUID;

/**
 * View model for one notification, with its message already localized server-side so both the
 * Thymeleaf page and the bell dropdown (populated via AJAX) display the same ready-to-show text.
 *
 * @param id notification id
 * @param text the localized notification text
 * @param read whether the notification is read
 * @param createdAtDisplay the creation instant formatted for display (UTC)
 * @param entityType originating aggregate type tag, or {@code null}
 * @param entityId originating aggregate id, or {@code null}
 * @param href same-origin path of the page the notification is about, or {@code null} when the web
 *     app has no page the recipient can open for it
 */
@DtoMirror
public record NotificationViewDto(
    UUID id,
    String text,
    boolean read,
    String createdAtDisplay,
    String entityType,
    UUID entityId,
    String href) {}
