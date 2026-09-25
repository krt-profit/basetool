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

package de.greluc.krt.profit.basetool.backend.support;

import org.jetbrains.annotations.NotNull;

/**
 * An {@link org.springframework.security.core.Authentication} that carries an OIDC subject without
 * a token behind it, such as the ingest gateway's acting-member authentication (ADR-0129).
 *
 * <p>Implementing it asserts that {@link #subject()} is an OIDC {@code sub}; {@link
 * AuthenticatedSubject} never falls back to {@code getName()}, which may be a callsign.
 */
public interface SubjectAuthentication {

  /**
   * The OIDC subject this authentication stands for.
   *
   * @return the non-blank {@code sub}, never a username, display name or callsign
   */
  @NotNull
  String subject();
}
