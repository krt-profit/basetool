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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.model.projection.BankHolderBalance;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

/**
 * Pure unit tests for {@link BankPdfFormat}: the German whole-aUEC number formatting (grouping,
 * ledger-scale dropping, the signed-column sign rule — REQ-BANK-005) that every bank statement and
 * three-month report renders with, and the holder-balance table's zero-balance skip plus
 * empty-state fallback (ADR-0039). The formatting is asserted directly on the static helpers; the
 * table is rendered into a throwaway {@link KrtPdfSupport.KrtDocument} and read back via {@code
 * PdfTextExtractor}, the same channel the report integration tests use. The message resolver is
 * stubbed as an identity {@link UnaryOperator} so the assertions can match the literal bundle keys.
 */
class BankPdfFormatTest {

  /**
   * Gap 1: German thousands grouping (dot separator), ledger-scale dropping and the signed-column
   * sign rule. Uses grouping-triggering magnitudes (the existing report tests only ever use
   * sub-1000 amounts), pins zero to render without a {@code +} (signum 0 is not {@code > 0}) and
   * relies on {@link java.text.DecimalFormat}'s own {@code -} prefix for negatives.
   */
  @Test
  void formattingUsesGermanGroupingAndSignRules() {
    // amount(): ledger scale (1850000.0000) is dropped and grouped with the German dot separator.
    assertEquals("1.850.000 aUEC", BankPdfFormat.amount(new BigDecimal("1850000.0000")));
    // groupedAmount(): the same grouping without the unit suffix (chart axis labels).
    assertEquals("1.850.000", BankPdfFormat.groupedAmount(new BigDecimal("1850000")));
    // signedAmount(): positive gets an explicit '+', negative keeps DecimalFormat's own '-',
    // and zero (signum == 0, not > 0) must render WITHOUT a leading '+'.
    assertEquals("+250.000", BankPdfFormat.signedAmount(new BigDecimal("250000")));
    assertEquals("-100.000", BankPdfFormat.signedAmount(new BigDecimal("-100000")));
    assertEquals("0", BankPdfFormat.signedAmount(BigDecimal.ZERO));
  }

  /**
   * Gap 2a: {@link BankPdfFormat#distributionTable} skips holders whose balance signum is 0, so no
   * spurious 0-aUEC custody row is printed for them, while the non-zero holder (and its grouped
   * amount) still renders.
   *
   * @throws IOException when the rendered PDF cannot be parsed
   */
  @Test
  void distributionTableSkipsZeroBalanceHolders() throws IOException {
    List<BankHolderBalance> distribution =
        List.of(
            new BankHolderBalance(
                UUID.randomUUID(), "activeholderrow", true, new BigDecimal("1850000")),
            new BankHolderBalance(UUID.randomUUID(), "zeroholderrow", true, BigDecimal.ZERO));

    String text = renderTable(distribution);

    assertTrue(text.contains("activeholderrow"), "the non-zero holder renders");
    assertTrue(text.contains("1.850.000 aUEC"), "the non-zero holder's grouped amount renders");
    assertFalse(text.contains("zeroholderrow"), "the zero-balance holder is skipped");
    assertFalse(
        text.contains("pdf.bank.distribution.empty"),
        "the empty-state row is not shown when a non-zero holder remains");
  }

  /**
   * Gap 2b: when every holder has a zero balance (all filtered out), {@link
   * BankPdfFormat#distributionTable} renders the localized empty-state row and none of the filtered
   * holders' handles.
   *
   * @throws IOException when the rendered PDF cannot be parsed
   */
  @Test
  void distributionTableEmptyRendersEmptyState() throws IOException {
    List<BankHolderBalance> distribution =
        List.of(
            new BankHolderBalance(UUID.randomUUID(), "zeroholderone", true, BigDecimal.ZERO),
            new BankHolderBalance(UUID.randomUUID(), "zeroholdertwo", false, BigDecimal.ZERO));

    String text = renderTable(distribution);

    assertTrue(
        text.contains("pdf.bank.distribution.empty"),
        "the empty-state row renders when no non-zero holder remains");
    assertFalse(text.contains("zeroholderone"), "no zero-balance holder row is printed");
    assertFalse(text.contains("zeroholdertwo"), "no zero-balance holder row is printed");
  }

  /**
   * Renders {@link BankPdfFormat#distributionTable} into a standalone KRT document with an identity
   * message resolver and returns the extracted text of the resulting PDF.
   *
   * @param distribution the holder balances to render
   * @return the concatenated page text of the rendered PDF
   * @throws IOException when the rendered PDF cannot be parsed
   */
  private static String renderTable(List<BankHolderBalance> distribution) throws IOException {
    UnaryOperator<String> label = UnaryOperator.identity();
    byte[] pdf;
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      KrtPdfSupport.KrtDocument krt = KrtPdfSupport.open(baos);
      PdfPTable table = BankPdfFormat.distributionTable(distribution, label);
      krt.document().add(table);
      krt.document().close();
      pdf = baos.toByteArray();
    }
    PdfReader reader = new PdfReader(pdf);
    try {
      PdfTextExtractor extractor = new PdfTextExtractor(reader);
      StringBuilder sb = new StringBuilder();
      for (int i = 1; i <= reader.getNumberOfPages(); i++) {
        sb.append(extractor.getTextFromPage(i)).append('\n');
      }
      return sb.toString();
    } finally {
      reader.close();
    }
  }
}
