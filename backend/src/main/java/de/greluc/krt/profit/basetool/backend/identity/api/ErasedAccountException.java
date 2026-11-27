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

package de.greluc.krt.profit.basetool.backend.identity.api;

import java.io.Serial;
import org.springframework.security.authentication.DisabledException;

/**
 * A still-valid token belongs to a deleted account (REQ-DATA-048); the request is refused as
 * unauthenticated and the account is not re-created.
 */
public class ErasedAccountException extends DisabledException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the refusal; the message names no person. */
  public ErasedAccountException() {
    super("The account was deleted");
  }
}
