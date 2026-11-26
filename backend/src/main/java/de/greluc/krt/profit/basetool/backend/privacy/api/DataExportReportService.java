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

package de.greluc.krt.profit.basetool.backend.privacy.api;

import de.greluc.krt.profit.basetool.backend.platform.api.pdf.KrtPdfDocuments;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * Renders a member's data export as the human-readable PDF half of the Art. 15 answer
 * (REQ-SEC-058).
 */
@Service
@RequiredArgsConstructor
public class DataExportReportService {

  private final DataExportLabels labels;
  private final KrtPdfDocuments pdfDocuments;

  /**
   * Renders an already-assembled export as a PDF, so the caller keeps the export for auditing.
   *
   * @param export the member's assembled export
   * @return the PDF bytes
   */
  public byte @NotNull [] renderPdf(@NotNull DataExportService.DataExport export) {
    return DataExportPdfFormat.render(pdfDocuments, export, labels::label);
  }
}
