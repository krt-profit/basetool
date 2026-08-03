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

import java.util.UUID;

/**
 * The one question the Terms-of-Use boundary asks (REQ-SEC-028).
 *
 * <p>Exists to invert a package dependency, not to abstract for its own sake. {@code
 * config.TermsAcceptanceAccessFilter} needs the answer, but a {@code config -> service} edge closes
 * a cycle that {@code ArchitectureTest} rejects (ADR-0047). Declaring the question in the
 * dependency-free {@code support} leaf and letting {@code service.TermsAcceptanceService} implement
 * it points both packages at the leaf instead of at each other — the remedy the ArchUnit rule
 * names.
 */
@FunctionalInterface
public interface TermsConsentCheck {

  /**
   * Reports whether the user has accepted the Terms-of-Use version currently in force.
   *
   * @param userId the user's {@code app_user.id}, i.e. the Keycloak {@code sub}
   * @return {@code true} when consent for the current wording is on record
   */
  boolean hasAcceptedCurrentTerms(UUID userId);
}
