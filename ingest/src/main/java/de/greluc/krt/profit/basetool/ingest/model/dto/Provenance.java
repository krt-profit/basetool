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

package de.greluc.krt.profit.basetool.ingest.model.dto;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The producing tool's self-declaration, carried identically by both ingest payloads: the {@code
 * RefineryExtract} envelope (ADR-0008) and the blueprint export both open with {@code
 * schemaVersion} / {@code tool} / {@code toolVersion}.
 *
 * <p>Having one type for both is what lets the provenance check and the accepted-payload log line
 * be written once instead of per endpoint. It also closed a real gap: the blueprint body is opaque
 * to the gateway ({@code JsonNode}, the backend does the parsing), so that path used to log nothing
 * but a byte count — an extractor sending structurally odd blueprint exports was invisible, while
 * the refinery path had recorded its shape since day one.
 *
 * <p>Every field is client-supplied free text and therefore <b>never</b> an authentication signal —
 * see {@code ProvenanceGuard} for what the check is and is not worth. Fields are nullable because a
 * caller may simply omit them; a missing value is a normal input to the guard, not an error here.
 *
 * @param tool producing tool identifier, e.g. {@code basetool-sc-extractor}
 * @param toolVersion producing tool version, e.g. the extractor's MSI version
 * @param schemaVersion declared envelope contract version, or {@code null} when absent/non-numeric
 */
public record Provenance(
    @Nullable String tool, @Nullable String toolVersion, @Nullable Integer schemaVersion) {

  /** Field name of the producing tool in both payload envelopes. */
  private static final String FIELD_TOOL = "tool";

  /** Field name of the producing tool's version in both payload envelopes. */
  private static final String FIELD_TOOL_VERSION = "toolVersion";

  /** Field name of the declared contract version in both payload envelopes. */
  private static final String FIELD_SCHEMA_VERSION = "schemaVersion";

  /**
   * Reads the provenance triple out of an opaque payload body — the blueprint path, where the
   * gateway deliberately does not bind the export to a DTO (the backend owns that contract).
   *
   * <p>Reads defensively: a field of the wrong JSON type yields {@code null} rather than throwing,
   * because this runs on an unvalidated internet-facing body and a malformed value must degrade to
   * "no provenance declared" — which the guard then rejects on its own terms — instead of surfacing
   * as a parse crash.
   *
   * @param body the parsed payload body; must be a JSON object
   * @return the declared provenance, with {@code null} for any absent or non-conforming field
   */
  public static @NotNull Provenance from(@NotNull JsonNode body) {
    return new Provenance(
        textOrNull(body, FIELD_TOOL),
        textOrNull(body, FIELD_TOOL_VERSION),
        body.path(FIELD_SCHEMA_VERSION).isIntegralNumber()
            ? body.path(FIELD_SCHEMA_VERSION).intValue()
            : null);
  }

  /**
   * Builds the provenance of a bound refinery extract, so both endpoints hand the guard and the log
   * the same shape.
   *
   * @param extract the validated refinery extract
   * @return the extract's declared provenance
   */
  public static @NotNull Provenance from(@NotNull RefineryExtractDto extract) {
    return new Provenance(extract.tool(), extract.toolVersion(), extract.schemaVersion());
  }

  /**
   * Reads a field as text, mapping absent, non-textual and blank values alike to {@code null}.
   *
   * @param body the payload body
   * @param field the field name
   * @return the non-blank text value, or {@code null}
   */
  private static @Nullable String textOrNull(@NotNull JsonNode body, @NotNull String field) {
    JsonNode node = body.path(field);
    if (!node.isString()) {
      return null;
    }
    String value = node.asString();
    return value.isBlank() ? null : value;
  }
}
