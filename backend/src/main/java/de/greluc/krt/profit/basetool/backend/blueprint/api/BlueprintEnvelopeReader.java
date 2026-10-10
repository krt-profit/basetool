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

import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportPreviewDto;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import tools.jackson.databind.JsonNode;

/**
 * Reads the exchange contract's {@code basetool.blueprints} envelope for the blueprint upload
 * preview, the way the exchange's blueprint draft does (REQ-XCH-019, plan §5.3); implemented by the
 * exchange module.
 */
public interface BlueprintEnvelopeReader {

  /** The message key of an envelope of another major format version. */
  String FORMAT_VERSION_UNSUPPORTED = "error.personalBlueprint.formatVersionUnsupported";

  /** The message key of an envelope that breaks its shape. */
  String INVALID_ENVELOPE = "error.personalBlueprint.import.invalidEnvelope";

  /**
   * Previews how each blueprint of the envelope resolves for the owner. Nothing is persisted.
   *
   * @param ownerUserId the {@code app_user.id} the preview is for
   * @param envelope the uploaded JSON object whose {@code format} is {@code basetool.blueprints}
   * @return the preview with per-name rows and per-status counts
   * @throws de.greluc.krt.profit.basetool.backend.exception.BadRequestException with {@link
   *     #FORMAT_VERSION_UNSUPPORTED} for another major format version, with {@link
   *     #INVALID_ENVELOPE} for an envelope that breaks its shape
   */
  @NotNull
  BlueprintImportPreviewDto preview(@NotNull UUID ownerUserId, @NotNull JsonNode envelope);
}
