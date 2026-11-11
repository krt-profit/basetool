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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test-only materialization and synthetic response resources for bounded Bank report assertions.
 */
public final class BankExportTestSupport {
  private BankExportTestSupport() {}

  /**
   * Reads a completed export for PDF assertions and releases its private storage.
   *
   * @param export the generated report
   * @return the report bytes, bounded by the test's fixture
   */
  public static byte[] read(BankPdfExport export) {
    try (export) {
      return export.getContentAsByteArray();
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  /**
   * Supplies a reopenable synthetic resource without filesystem state for HTTP gate tests.
   *
   * @param bytes the synthetic response body
   * @return a mock export with the supplied body
   */
  public static BankPdfExport resource(byte[] bytes) {
    BankPdfExport export = mock(BankPdfExport.class);
    try {
      when(export.getInputStream()).thenAnswer(_ -> new ByteArrayInputStream(bytes));
      when(export.contentLength()).thenReturn((long) bytes.length);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
    return export;
  }

  /**
   * Creates a real private export directory for request cleanup tests.
   *
   * @param bytes the synthetic PDF body
   * @return the file-backed resource owned by the test request
   */
  public static BankPdfExport file(byte[] bytes) {
    BankReportSpool spool =
        BankReportSpool.create(
            new BankExportProperties(Path.of(System.getProperty("java.io.tmpdir"))));
    try {
      Files.write(spool.file("report.pdf"), bytes);
      return new BankPdfExport(spool);
    } catch (IOException failure) {
      spool.close();
      throw new UncheckedIOException(failure);
    }
  }
}
