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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import de.greluc.krt.profit.basetool.backend.kernel.DomainProblem;
import java.io.Serial;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a squadron write sets a storage place no picker offers, a hidden or a reference-only
 * one (REQ-P4K-039).
 *
 * <p>Answers {@code 400} with code {@code LOCATION_NOT_OFFERED} and a localized detail.
 */
public final class LocationNotOfferedException extends DomainProblem {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception; the message is the i18n key of the localized detail, so the RFC 7807
   * {@code detail} reaches every client in the caller's language.
   */
  public LocationNotOfferedException() {
    super("problem.location_not_offered.detail");
  }

  /**
   * The status the refusal is answered with.
   *
   * @return {@code 400}
   */
  @NotNull
  @Override
  public HttpStatus status() {
    return CatalogueProblemCode.LOCATION_NOT_OFFERED.status();
  }

  /**
   * The stable code of the refusal.
   *
   * @return {@code LOCATION_NOT_OFFERED}
   */
  @NotNull
  @Override
  public String code() {
    return CatalogueProblemCode.LOCATION_NOT_OFFERED.code();
  }

  /**
   * The label the handler's log line names the refusal by.
   *
   * @return {@code "Location not offered"}
   */
  @NotNull
  @Override
  public String logLabel() {
    return "Location not offered";
  }
}
