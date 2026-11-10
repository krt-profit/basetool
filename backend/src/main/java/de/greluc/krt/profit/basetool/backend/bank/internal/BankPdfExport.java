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

import java.io.IOException;
import java.io.InputStream;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.FileSystemResource;

/** Reopenable PDF resource whose request owner releases the private export directory. */
public final class BankPdfExport extends AbstractResource implements AutoCloseable {
  private final BankReportSpool spool;
  private final FileSystemResource pdf;

  BankPdfExport(BankReportSpool spool) {
    this.spool = spool;
    this.pdf = new FileSystemResource(spool.file("report.pdf"));
  }

  /** Returns a non-sensitive description of this generated PDF. */
  @Override
  public @NotNull String getDescription() {
    return "Generated Bank PDF export";
  }

  /** Opens a fresh stream; closing it leaves the resource available until request cleanup. */
  @Override
  public @NotNull InputStream getInputStream() throws IOException {
    return pdf.getInputStream();
  }

  /** Returns the completed PDF's byte length without opening it. */
  @Override
  public long contentLength() throws IOException {
    return pdf.contentLength();
  }

  /** Removes the PDF and its private snapshot files after response conversion completes. */
  @Override
  public void close() {
    spool.close();
  }
}
