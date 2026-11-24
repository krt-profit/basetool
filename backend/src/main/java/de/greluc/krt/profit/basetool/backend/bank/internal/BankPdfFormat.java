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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.KrtPdfDocument;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.PdfDataTable;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.PdfTableCell;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.NotNull;

/**
 * Bank-specific PDF formatting shared by the statement (REQ-BANK-014) and the three-month report
 * (REQ-BANK-015): German-grouped whole-aUEC amounts (the ledger only accepts whole amounts,
 * REQ-BANK-005) and the holder-balance table the three-month report ends with (the global holder
 * custody totals since ADR-0039 decoupled holders from accounts).
 */
public final class BankPdfFormat {

  /** Relative widths of the holder and amount columns of the holder-balance table. */
  private static final float[] HOLDER_WIDTHS = {3f, 1.5f};

  private BankPdfFormat() {}

  /**
   * Renders the "Quell-/Zielkonto" cell of a statement row (REQ-BANK-044): the counterparty with
   * org unit for a {@code DEPOSIT}/{@code WITHDRAWAL}, the counter account's number for a {@code
   * TRANSFER}, empty otherwise.
   *
   * @param row the statement row
   * @param accountLegsByTx the page's account legs grouped by transaction
   * @param anonymisedLabel the localized name for erased users
   * @return the cell text, never {@code null}
   */
  public static @NotNull String counterpartyCell(
      @NotNull BankBookingRow row,
      @NotNull Map<UUID, List<BankCounterLeg>> accountLegsByTx,
      @NotNull String anonymisedLabel) {
    return switch (row.type()) {
      case DEPOSIT, WITHDRAWAL -> {
        if (row.counterpartyHandle() == null) {
          yield "";
        }
        String handle =
            HandleAnonymisation.humaniseRequired(row.counterpartyHandle(), anonymisedLabel);
        yield row.counterpartyOrgUnitName() == null
            ? handle
            : handle + " (" + row.counterpartyOrgUnitName() + ")";
      }
      case TRANSFER ->
          accountLegsByTx.getOrDefault(row.transactionId(), List.of()).stream()
              .filter(leg -> !leg.postingId().equals(row.postingId()))
              .map(BankCounterLeg::accountNo)
              .findFirst()
              .orElse("");
      default -> "";
    };
  }

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
   * Draws the holder-balance table (HALTER / BETRAG) of the global holder custody totals: one row
   * per holder with a non-zero balance, or the localized empty row.
   *
   * @param pdf the open document
   * @param distribution the per-holder balances to render
   * @param label the message-bundle resolver of the calling service
   */
  public static void distributionTable(
      @NotNull KrtPdfDocument pdf,
      @NotNull List<BankHolderBalance> distribution,
      @NotNull UnaryOperator<String> label) {
    try (PdfDataTable table = holderTable(pdf, label)) {
      boolean any = false;
      for (BankHolderBalance slice : distribution) {
        if (slice.amount().signum() != 0) {
          any = true;
          holderRow(table, slice);
        }
      }
      if (!any) {
        table.emptyRow(label.apply("pdf.bank.distribution.empty"));
      }
    }
  }

  /**
   * Starts the holder-balance table with its HALTER / BETRAG header.
   *
   * @param pdf the open document
   * @param label the message-bundle resolver of the calling service
   * @return the open table
   */
  public static @NotNull PdfDataTable holderTable(
      @NotNull KrtPdfDocument pdf, @NotNull UnaryOperator<String> label) {
    return pdf.dataTable(
        HOLDER_WIDTHS,
        List.of(label.apply("pdf.bank.col.holder"), label.apply("pdf.bank.col.amount")));
  }

  /**
   * Adds one holder and its balance to a holder-balance table.
   *
   * @param table the table from {@link #holderTable}
   * @param balance the holder's custody total
   */
  public static void holderRow(@NotNull PdfDataTable table, @NotNull BankHolderBalance balance) {
    table.row(
        List.of(
            PdfTableCell.text(balance.handle()), PdfTableCell.centered(amount(balance.amount()))));
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
