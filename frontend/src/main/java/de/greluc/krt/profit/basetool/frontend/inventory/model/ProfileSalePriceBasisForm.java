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

package de.greluc.krt.profit.basetool.frontend.inventory.model;

import jakarta.validation.constraints.NotNull;

/**
 * The profile's "Mein Lager" price basis section, as the page renders it and posts it back
 * (REQ-INV-072).
 *
 * @param salePriceBasis the chosen basis
 * @param version the setting's version the page last read
 */
public record ProfileSalePriceBasisForm(
    @NotNull SalePriceBasis salePriceBasis, @NotNull Long version) {

  /**
   * The backend request this form posts.
   *
   * @return the basis and the version
   */
  public MySalePriceBasisRequest toRequest() {
    return new MySalePriceBasisRequest(salePriceBasis, version);
  }
}
