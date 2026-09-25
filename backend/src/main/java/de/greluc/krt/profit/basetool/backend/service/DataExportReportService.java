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

import de.greluc.krt.profit.basetool.backend.service.pdf.DataExportPdfFormat;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

/**
 * Renders a member's data export as the human-readable PDF half of the Art. 15 answer
 * (REQ-SEC-058).
 */
@Service
@RequiredArgsConstructor
public class DataExportReportService {

  private final DataExportService dataExportService;
  private final MessageSource messageSource;

  /**
   * Renders an already-assembled export as a PDF, so the caller keeps the export for auditing.
   *
   * @param export the member's assembled export
   * @return the PDF bytes
   */
  public byte @NotNull [] renderPdf(@NotNull DataExportService.DataExport export) {
    return DataExportPdfFormat.render(export, this::label);
  }

  /**
   * Resolves one German PDF label from the backend message bundle.
   *
   * @param key the message key
   * @return the resolved label, or the key itself when it is missing
   */
  private @NotNull String label(@NotNull String key) {
    return messageSource.getMessage(key, null, key, Locale.GERMAN);
  }
}
