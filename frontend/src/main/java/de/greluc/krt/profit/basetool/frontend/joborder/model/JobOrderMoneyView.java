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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.math.BigInteger;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;

/**
 * Monetary totals, history and the caller's current aUEC order actions.
 *
 * @param target the requested net amount
 * @param targetVersion the target's independent version
 * @param provided the effective provisions
 * @param holdings the current order holdings
 * @param netHandedOver the effective net delivered amount
 * @param remaining the still outstanding net target
 * @param holders the current order-local holdings
 * @param history the visible provisions
 * @param canProvideSelf whether the reader can report their own funds
 * @param canProvideOnBehalf whether the writer can report another player's funds
 * @param canDelete whether the caller can hard-delete the order
 */
@DtoMirror
public record JobOrderMoneyView(
    String target,
    long targetVersion,
    String provided,
    String holdings,
    String netHandedOver,
    String remaining,
    List<JobOrderMoneyHolderView> holders,
    List<JobOrderMoneyOutcome> history,
    boolean canProvideSelf,
    boolean canProvideOnBehalf,
    boolean canDelete) {

  /**
   * Groups a canonical whole amount exactly for the page language.
   *
   * @param amount the canonical decimal digits supplied by the backend
   * @param locale the page language
   * @return the localized integer
   */
  public String formatAmount(@NotNull String amount, @NotNull Locale locale) {
    return NumberFormat.getIntegerInstance(locale).format(new BigInteger(amount));
  }
}
