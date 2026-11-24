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
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.PDFAValidator;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.results.TestAssertion;
import org.verapdf.pdfa.results.ValidationResult;

/**
 * Validates a rendered PDF against PDF/A-4 (ISO 19005-4:2020) with veraPDF, the PDF Association's
 * reference validator (REQ-API-026). A failure lists every failed rule with its clause.
 */
public final class PdfA4Conformance {

  static {
    VeraGreenfieldFoundryProvider.initialise();
  }

  private PdfA4Conformance() {}

  /**
   * Asserts that the bytes are a PDF/A-4 file without a single failed rule.
   *
   * @param pdf the rendered PDF
   * @throws Exception when veraPDF cannot parse or validate the file
   */
  public static void assertConformant(byte @NotNull [] pdf) throws Exception {
    try (PDFAParser parser =
            Foundries.defaultInstance()
                .createParser(new ByteArrayInputStream(pdf), PDFAFlavour.PDFA_4);
        PDFAValidator validator =
            Foundries.defaultInstance().createValidator(PDFAFlavour.PDFA_4, false)) {
      ValidationResult result = validator.validate(parser);
      assertThat(result.isCompliant()).as(() -> failures(result)).isTrue();
    }
  }

  private static @NotNull String failures(@NotNull ValidationResult result) {
    return result.getTestAssertions().stream()
        .filter(assertion -> assertion.getStatus() == TestAssertion.Status.FAILED)
        .map(assertion ->
            assertion.getRuleId().getClause()
                + "-"
                + assertion.getRuleId().getTestNumber()
                + ": "
                + assertion.getMessage()
                + (assertion.getLocationContext() == null
                    ? ""
                    : " @ " + assertion.getLocationContext()))
        .distinct()
        .collect(Collectors.joining("\n", "PDF/A-4 rules failed:\n", ""));
  }
}
