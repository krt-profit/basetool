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

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportPreviewDto;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Previews an uploaded blueprint file: the {@code basetool.blueprints} envelope of the exchange
 * contract as the exchange's blueprint draft does (REQ-XCH-019), any other shape through the export
 * parser (REQ-INV-014).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlueprintUploadPreviewService {

  /** The format name of the exchange's blueprint envelope. */
  static final String ENVELOPE_FORMAT = "basetool.blueprints";

  /** The largest upload, the export parser's own cap. */
  private static final long MAX_UPLOAD_BYTES = 8L * 1024 * 1024;

  private final ObjectMapper objectMapper;
  private final BlueprintImportService importService;
  private final BlueprintEnvelopeReader envelopeReader;

  /**
   * Previews how each blueprint of the uploaded file resolves for the owner. Nothing is persisted.
   *
   * @param ownerUserId the {@code app_user.id} the preview is for
   * @param file the uploaded file
   * @return the preview with per-name rows and per-status counts
   * @throws BadRequestException if the file is empty, too large, not valid JSON, an invalid
   *     envelope, an envelope of a format major other than {@code 1}, or carries no blueprint array
   */
  public @NotNull BlueprintImportPreviewDto preview(
      @NotNull UUID ownerUserId, @NotNull MultipartFile file) {
    if (file.isEmpty() || file.getSize() > MAX_UPLOAD_BYTES) {
      return importService.previewImport(ownerUserId, file);
    }
    JsonNode root;
    try {
      root = objectMapper.readTree(file.getInputStream());
    } catch (IOException | JacksonException _) {
      return importService.previewImport(ownerUserId, file);
    }
    if (root == null
        || !root.isObject()
        || !ENVELOPE_FORMAT.equals(root.path("format").asString(null))) {
      return importService.previewImport(ownerUserId, file);
    }
    return envelopeReader.preview(ownerUserId, root);
  }
}
