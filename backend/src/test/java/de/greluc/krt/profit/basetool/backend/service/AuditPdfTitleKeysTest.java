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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.AuditDomain;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins that every audited area has the PDF title {@link AuditReportService} resolves without a
 * default, in every backend bundle, so an area's PDF export can never fail on a missing key
 * (REQ-AUDIT-003).
 */
class AuditPdfTitleKeysTest {

  @ParameterizedTest
  @ValueSource(
      strings = {"messages.properties", "messages_de.properties", "messages_en.properties"})
  void everyAuditDomainHasAPdfTitleInEveryBundle(String bundle) throws IOException {
    Properties properties = load(bundle);
    for (AuditDomain domain : AuditDomain.values()) {
      assertThat(properties.getProperty("pdf.audit.title." + domain.name()))
          .as(bundle + ": pdf.audit.title." + domain.name())
          .isNotBlank();
    }
  }

  /**
   * Loads one backend bundle from the classpath as UTF-8.
   *
   * @param bundle the bundle's file name
   * @return its keys and values
   * @throws IOException when the bundle cannot be read
   */
  private static @NotNull Properties load(@NotNull String bundle) throws IOException {
    Properties properties = new Properties();
    InputStream in = AuditPdfTitleKeysTest.class.getClassLoader().getResourceAsStream(bundle);
    assertThat(in).as(bundle).isNotNull();
    try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    return properties;
  }
}
