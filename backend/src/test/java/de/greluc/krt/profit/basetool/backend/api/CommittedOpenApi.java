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

package de.greluc.krt.profit.basetool.backend.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.jetbrains.annotations.NotNull;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The backend's two committed OpenAPI documents: how they are read, rendered and refreshed
 * (REQ-API-007, REQ-XCH-039).
 */
public final class CommittedOpenApi {

  /** The published document on the classpath. */
  public static final String PUBLISHED_RESOURCE = "/api/openapi.json";

  /** The exchange's internal relay document on the classpath. */
  public static final String RELAY_RESOURCE = "/api/exchange-relay.openapi.json";

  /** The published document, relative to the backend module. */
  public static final Path PUBLISHED_FILE = Path.of("src/main/resources/api/openapi.json");

  /** The relay document, relative to the backend module. */
  public static final Path RELAY_FILE =
      Path.of("src/main/resources/api/exchange-relay.openapi.json");

  /** Reads and writes the documents. */
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** Two-space indentation with LF line ends on every platform. */
  private static final DefaultPrettyPrinter PRINTER =
      new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n"));

  /** Not instantiable. */
  private CommittedOpenApi() {}

  /**
   * Reads the published document off the classpath.
   *
   * @return the parsed document
   * @throws UncheckedIOException if it is missing or unreadable
   */
  public static @NotNull JsonNode published() {
    return read(PUBLISHED_RESOURCE);
  }

  /**
   * Reads the relay document off the classpath.
   *
   * @return the parsed document
   * @throws UncheckedIOException if it is missing or unreadable
   */
  public static @NotNull JsonNode relay() {
    return read(RELAY_RESOURCE);
  }

  /**
   * Joins both committed documents, for the guards that compare the whole frozen surface.
   *
   * @return the published document with the relay's paths and components added
   */
  public static @NotNull JsonNode merged() {
    return ExchangeFence.merge(published(), relay());
  }

  /**
   * Renders a document as it is committed: pretty-printed, two-space indent, LF line ends.
   *
   * @param document the document
   * @return the rendered text, without a trailing line break
   */
  public static @NotNull String render(@NotNull JsonNode document) {
    return MAPPER.writer().with(PRINTER).writeValueAsString(document);
  }

  /**
   * Brings a committed document up to date, or reports that it is stale.
   *
   * <p>Line ends are ignored in the comparison. When the file differs and {@code rewrite} is set,
   * it is replaced atomically through a temporary sibling, so a concurrent reader never sees a
   * partial file.
   *
   * @param target the committed file
   * @param content the content the build generated
   * @param rewrite whether a stale file is replaced rather than reported
   * @return {@code true} when the file was stale
   * @throws AssertionError if the file is stale and {@code rewrite} is not set
   * @throws IOException if the file cannot be read or written
   */
  public static boolean refresh(@NotNull Path target, @NotNull String content, boolean rewrite)
      throws IOException {
    String committed =
        Files.isRegularFile(target)
            ? Files.readString(target, StandardCharsets.UTF_8).replace("\r\n", "\n")
            : null;
    if (content.equals(committed)) {
      return false;
    }
    if (!rewrite) {
      throw new AssertionError(
          target
              + " is stale: it differs from the document the controllers generate. Run ./gradlew"
              + " :backend:test --tests '*.OpenApiGeneratorTest' and commit the result");
    }
    Path directory = target.toAbsolutePath().getParent();
    Files.createDirectories(directory);
    Path temporary = Files.createTempFile(directory, "openapi-", ".json.tmp");
    try {
      Files.writeString(temporary, content, StandardCharsets.UTF_8);
      try {
        Files.move(
            temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
    return true;
  }

  /**
   * Reads one document off the classpath.
   *
   * @param resource the classpath resource
   * @return the parsed document
   */
  private static JsonNode read(String resource) {
    try (InputStream in = CommittedOpenApi.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IOException(resource + " is not on the classpath");
      }
      return MAPPER.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
