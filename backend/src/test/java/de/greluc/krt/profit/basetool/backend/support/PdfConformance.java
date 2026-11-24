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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.PDFAValidator;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.results.TestAssertion;
import org.verapdf.pdfa.results.ValidationResult;
import org.verapdf.pdfa.validation.validators.ValidatorFactory;

/**
 * Validates a rendered PDF with veraPDF, the PDF Association's reference validator, against every
 * profile a backend PDF claims: PDF/A-4, PDF/UA-2 and both Well-Tagged PDF 1.0 conformance levels
 * (REQ-API-026, REQ-API-029). A failure lists each failed rule once, with its profile and clause.
 */
public final class PdfConformance {

  /** The profiles every backend PDF conforms to. */
  public static final List<PDFAFlavour> PROFILES =
      List.of(
          PDFAFlavour.PDFA_4,
          PDFAFlavour.PDFUA_2,
          PDFAFlavour.WTPDF_1_0_ACCESSIBILITY,
          PDFAFlavour.WTPDF_1_0_REUSE);

  static {
    VeraGreenfieldFoundryProvider.initialise();
  }

  private PdfConformance() {}

  /**
   * Asserts that the bytes conform to every profile in {@link #PROFILES} without a failed rule.
   *
   * @param pdf the rendered PDF
   * @throws Exception when veraPDF cannot parse or validate the file
   */
  public static void assertConformant(byte @NotNull [] pdf) throws Exception {
    assertThat(failures(pdf, PROFILES)).as("veraPDF failures").isEmpty();
  }

  /**
   * Validates the bytes against the given profiles and lists every failed rule.
   *
   * @param pdf the rendered PDF
   * @param profiles the profiles to validate against
   * @return one line per failed rule, {@code <profile> <clause>-<test>: <message>}; empty when the
   *     file conforms
   * @throws Exception when veraPDF cannot parse or validate the file
   */
  public static @NotNull String failures(byte @NotNull [] pdf, @NotNull List<PDFAFlavour> profiles)
      throws Exception {
    try (PDFAParser parser =
            Foundries.defaultInstance()
                .createParser(new ByteArrayInputStream(pdf), PDFAFlavour.PDFUA_2);
        PDFAValidator validator = ValidatorFactory.createValidator(profiles)) {
      Map<String, String> failed = new LinkedHashMap<>();
      for (ValidationResult result : validator.validateAll(parser)) {
        for (TestAssertion assertion : result.getTestAssertions()) {
          if (assertion.getStatus() != TestAssertion.Status.FAILED) {
            continue;
          }
          String rule =
              result.getPDFAFlavour().getId()
                  + " "
                  + assertion.getRuleId().getClause()
                  + "-"
                  + assertion.getRuleId().getTestNumber();
          failed.putIfAbsent(
              rule,
              assertion.getMessage()
                  + (assertion.getLocationContext() == null
                      ? ""
                      : " @ " + assertion.getLocationContext()));
        }
      }
      return failed.entrySet().stream()
          .map(entry -> entry.getKey() + ": " + entry.getValue())
          .collect(Collectors.joining("\n"));
    }
  }
}
