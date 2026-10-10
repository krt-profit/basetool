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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBlueprintDraftDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportPreviewDto;
import de.greluc.krt.profit.basetool.backend.service.BlueprintEnvelopeReader;
import jakarta.validation.Validator;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The exchange's reader of the {@code basetool.blueprints} envelope for the blueprint upload
 * preview: the envelope is bound and validated as the exchange's blueprint draft binds it, then
 * resolved by {@link ExchangeDraftService#blueprints} (REQ-XCH-019).
 */
@Component
@RequiredArgsConstructor
public class ExchangeBlueprintEnvelopeReader implements BlueprintEnvelopeReader {

  /** The envelope field naming the format version. */
  private static final String FORMAT_VERSION = "formatVersion";

  /** A format version as the envelope schema allows it. */
  private static final Pattern WELL_FORMED_VERSION = Pattern.compile("^[0-9]+\\.[0-9]+$");

  /** The format versions this Basetool reads. */
  private static final Pattern SUPPORTED_VERSION =
      Pattern.compile(ExchangeBlueprintDraftDto.SUPPORTED_FORMAT_VERSION);

  private final ObjectMapper objectMapper;
  private final Validator validator;
  private final ExchangeDraftService draftService;

  @Override
  @NotNull
  public BlueprintImportPreviewDto preview(@NotNull UUID ownerUserId, @NotNull JsonNode envelope) {
    if (isOtherMajor(envelope.get(FORMAT_VERSION))) {
      throw new BadRequestException(FORMAT_VERSION_UNSUPPORTED);
    }
    ExchangeBlueprintDraftDto draft;
    try {
      draft = objectMapper.treeToValue(envelope, ExchangeBlueprintDraftDto.class);
    } catch (JacksonException _) {
      throw new BadRequestException(INVALID_ENVELOPE);
    }
    if (draft == null || !validator.validate(draft).isEmpty()) {
      throw new BadRequestException(INVALID_ENVELOPE);
    }
    return draftService.blueprints(ownerUserId, draft);
  }

  /**
   * Tells whether an envelope's format version is well-formed but of a major version other than
   * {@code 1}, which this Basetool cannot read (REQ-XCH-019, REQ-INV-014).
   *
   * @param version the envelope's {@code formatVersion}, or {@code null} when absent
   * @return {@code true} for a {@code <major>.<minor>} string whose major is not {@code 1}
   */
  static boolean isOtherMajor(@Nullable JsonNode version) {
    if (version == null || !version.isString()) {
      return false;
    }
    String value = version.stringValue();
    return WELL_FORMED_VERSION.matcher(value).matches()
        && !SUPPORTED_VERSION.matcher(value).matches();
  }
}
