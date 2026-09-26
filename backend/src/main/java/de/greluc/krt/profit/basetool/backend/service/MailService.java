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
 * Channel-agnostic seam for sending a transactional e-mail (REQ-NOTIF-013).
 *
 * <p>Sending is best-effort: an implementation must not throw on a delivery failure, a disabled
 * channel or an unconfigured transport; it logs and returns.
 */
public interface MailService {

  /**
   * Sends the given message, best-effort. Never throws on a delivery failure, a disabled channel
   * ({@code app.mail.enabled=false}) or a missing SMTP transport ({@code spring.mail.host} unset);
   * such cases are logged and the message is dropped.
   *
   * @param message the message to send
   */
  void send(@NotNull MailMessage message);
}
