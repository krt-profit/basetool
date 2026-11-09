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

package de.greluc.krt.profit.basetool.backend.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Checks a backend answer against the published v1 schemas of the ingest gateway as the gateway
 * checks it, read from the gateway's committed schema folder.
 */
public final class ExchangePublishedSchemas {

  /** The permanent base of every published schema {@code $id}. */
  private static final String BASE = "https://ingest.profit-base.online/exchange/v1/schemas/";

  /** The gateway's committed schema folder, relative to the backend module. */
  private static final Path SCHEMAS = Path.of("../ingest/src/main/resources/exchange/v1/schemas");

  private static final SchemaRegistry REGISTRY =
      SchemaRegistry.withDefaultDialect(
          SpecificationVersion.DRAFT_2020_12,
          builder -> builder.schemas(ExchangePublishedSchemas::publishedSchema));

  /** Not instantiable. */
  private ExchangePublishedSchemas() {}

  /**
   * Asserts that a document validates against a published schema.
   *
   * @param schema the schema's file name, optionally with a fragment
   * @param json the document
   * @param what the document's origin, for the message
   */
  public static void assertMatches(
      @Nullable String schema, @NotNull String json, @NotNull String what) {
    assertThat(schema).as("schema of " + what).isNotNull();
    List<Error> errors =
        REGISTRY.getSchema(SchemaLocation.of(BASE + schema)).validate(json, InputFormat.JSON);
    assertThat(errors).as("%s against %s: %s", what, schema, json).isEmpty();
  }

  /**
   * Reads a published schema by its {@code $id}.
   *
   * @param schemaId the schema's {@code $id}
   * @return its text, or {@code null} for a schema that is not one of the published ones
   */
  private static @Nullable String publishedSchema(@NotNull String schemaId) {
    if (!schemaId.startsWith(BASE)) {
      return null;
    }
    try {
      return Files.readString(
          SCHEMAS.resolve(schemaId.substring(BASE.length())), StandardCharsets.UTF_8);
    } catch (IOException missing) {
      return null;
    }
  }
}
