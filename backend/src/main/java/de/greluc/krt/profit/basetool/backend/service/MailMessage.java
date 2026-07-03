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

package de.greluc.krt.profit.basetool.backend.service;

import org.jetbrains.annotations.NotNull;

/**
 * Immutable value object describing one plain-text e-mail handed to {@link MailService}.
 *
 * <p>Channel-agnostic and free of any domain concept (approval, notification, …) so the same seam
 * serves any future producer — including the in-app notification system routing a notification to
 * e-mail as a second delivery channel (REQ-NOTIF-013). Callers localize the subject and body before
 * constructing it.
 *
 * @param to the recipient's e-mail address
 * @param subject the already-localized subject line
 * @param body the already-localized plain-text body
 */
public record MailMessage(@NotNull String to, @NotNull String subject, @NotNull String body) {}
