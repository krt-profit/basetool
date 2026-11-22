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
 * Thrown when a write sets a ship type no picker offers: one the game files do not list, a hidden
 * or a retired one (REQ-P4K-080).
 *
 * <p>Answers {@code 400} with code {@code CATALOGUE_SHIP_TYPE_NOT_OFFERED} and a localized detail.
 */
public final class ShipTypeNotOfferedException extends DomainProblem {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception; the message is the i18n key of the localized detail, so the RFC 7807
   * {@code detail} reaches every client in the caller's language.
   */
  public ShipTypeNotOfferedException() {
    super("problem.catalogue_ship_type_not_offered.detail");
  }

  /**
   * The status the refusal is answered with.
   *
   * @return {@code 400}
   */
  @NotNull
  @Override
  public HttpStatus status() {
    return CatalogueProblemCode.CATALOGUE_SHIP_TYPE_NOT_OFFERED.status();
  }

  /**
   * The stable code of the refusal.
   *
   * @return {@code CATALOGUE_SHIP_TYPE_NOT_OFFERED}
   */
  @NotNull
  @Override
  public String code() {
    return CatalogueProblemCode.CATALOGUE_SHIP_TYPE_NOT_OFFERED.code();
  }

  /**
   * The label the handler's log line names the refusal by.
   *
   * @return {@code "Ship type not offered"}
   */
  @NotNull
  @Override
  public String logLabel() {
    return "Ship type not offered";
  }
}
