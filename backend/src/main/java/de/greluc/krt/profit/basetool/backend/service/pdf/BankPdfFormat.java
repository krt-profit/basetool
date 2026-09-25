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

package de.greluc.krt.profit.basetool.backend.service.pdf;

import de.greluc.krt.profit.basetool.backend.model.projection.BankHolderBalance;
import java.awt.Color;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.NotNull;
import org.openpdf.text.pdf.PdfPTable;

/**
 * Bank-specific PDF formatting shared by the statement (REQ-BANK-014) and the three-month report
 * (REQ-BANK-015): German-grouped whole-aUEC amounts (the ledger only accepts whole amounts,
 * REQ-BANK-005) and the holder-balance table the three-month report ends with (the global holder
 * custody totals since ADR-0039 decoupled holders from accounts).
 */
public final class BankPdfFormat {

  private BankPdfFormat() {}

  /**
   * Formats a balance as German-grouped whole aUEC, e.g. {@code 1.850.000 aUEC}.
   *
   * @param value the amount (whole by ledger validation; trailing ledger scale is dropped)
   * @return the formatted balance with unit suffix
   */
  public static @NotNull String amount(@NotNull BigDecimal value) {
    return grouped(value) + " aUEC";
  }

  /**
   * German-grouped whole-number rendering without the unit suffix — the compact form used for the
   * balance chart's axis labels, where the {@code aUEC} suffix would not fit.
   *
   * @param value the amount
   * @return the grouped digits, no unit
   */
  public static @NotNull String groupedAmount(@NotNull BigDecimal value) {
    return grouped(value);
  }

  /**
   * Formats a posting amount signed for the booking column, e.g. {@code +250.000} / {@code
   * -100.000}.
   *
   * @param value the signed posting amount
   * @return the formatted amount with an explicit plus sign for inflows
   */
  public static @NotNull String signedAmount(@NotNull BigDecimal value) {
    String formatted = grouped(value);
    return value.signum() > 0 ? "+" + formatted : formatted;
  }

  /**
   * Builds the holder-balance table (HALTER / BETRAG) of the global holder custody totals: one row
   * per holder with a non-zero balance, or the localized empty row.
   *
   * @param distribution the per-holder balances to render
   * @param label the message-bundle resolver of the calling service
   * @return the ready-to-add table
   */
  public static @NotNull PdfPTable distributionTable(
      @NotNull List<BankHolderBalance> distribution, @NotNull UnaryOperator<String> label) {
    PdfPTable table = new PdfPTable(2);
    table.setWidthPercentage(100);
    table.setWidths(new float[] {3f, 1.5f});
    KrtPdfSupport.addTableHeader(table, label.apply("pdf.bank.col.holder"));
    KrtPdfSupport.addTableHeader(table, label.apply("pdf.bank.col.amount"));

    boolean alt = false;
    boolean any = false;
    for (BankHolderBalance slice : distribution) {
      if (slice.amount().signum() == 0) {
        continue;
      }
      any = true;
      Color bg = KrtPdfSupport.rowBackground(alt);
      KrtPdfSupport.addTableCell(table, slice.handle(), bg, false);
      KrtPdfSupport.addTableCell(table, amount(slice.amount()), bg, true);
      alt = !alt;
    }
    if (!any) {
      KrtPdfSupport.addEmptyRow(table, label.apply("pdf.bank.distribution.empty"), 2);
    }
    return table;
  }

  /**
   * German-grouped integer rendering of a ledger amount ({@code 1850000.0000} → {@code 1.850.000}).
   *
   * @param value the amount
   * @return the grouped digits without unit
   */
  private static @NotNull String grouped(@NotNull BigDecimal value) {
    DecimalFormat format =
        new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.GERMANY));
    return format.format(value);
  }
}
