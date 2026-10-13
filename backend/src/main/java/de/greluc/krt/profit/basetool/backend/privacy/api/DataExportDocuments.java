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

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.ObjectMapper;

/**
 * Serialisation and download naming of a data export (REQ-SEC-058): the JSON document is indented
 * for a human reader, and both formats are named {@code Datenexport <account name> <yy-MM-dd>}.
 */
public final class DataExportDocuments {

  /** The zone the date in the filename is read in; the download link cannot carry the caller's. */
  static final ZoneId FILENAME_ZONE = ZoneId.of("Europe/Berlin");

  private static final DateTimeFormatter FILENAME_DATE = DateTimeFormatter.ofPattern("yy-MM-dd");

  private static final int MAX_NAME_LENGTH = 80;

  private static final String FALLBACK_NAME = "Konto";

  private static final DefaultPrettyPrinter PRETTY_PRINTER =
      new DefaultPrettyPrinter()
          .withObjectIndenter(new DefaultIndenter("  ", "\n"))
          .withArrayIndenter(new DefaultIndenter("  ", "\n"));

  private DataExportDocuments() {}

  /**
   * Serialises an export as indented UTF-8 JSON, two spaces per level, one member per line.
   *
   * @param mapper the application's mapper, so the content equals the unindented serialisation
   * @param export the export
   * @return the JSON bytes
   */
  public static byte @NotNull [] json(
      @NotNull ObjectMapper mapper, @NotNull DataExportService.DataExport export) {
    return mapper.writer().with(PRETTY_PRINTER).writeValueAsBytes(export);
  }

  /**
   * The attachment header of an export download, named {@code Datenexport <account name>
   * <yy-MM-dd>.<extension>}; characters a filesystem refuses are replaced and the name is capped.
   *
   * @param export the export, supplying the account name and the generation date
   * @param extension the file extension without the dot, {@code pdf} or {@code json}
   * @return the {@code Content-Disposition} header value
   */
  public static @NotNull String contentDisposition(
      @NotNull DataExportService.DataExport export, @NotNull String extension) {
    String date = FILENAME_DATE.format(export.generatedAt().atZone(FILENAME_ZONE));
    String filename = "Datenexport " + safeName(export.subjectHandle()) + " " + date;
    return ContentDisposition.attachment()
        .filename(filename + "." + extension, StandardCharsets.UTF_8)
        .build()
        .toString();
  }

  /**
   * Reduces an account name to something every filesystem accepts: reserved and control characters
   * become underscores, runs of whitespace collapse, and trailing dots and spaces are dropped.
   *
   * @param name the account name, possibly {@code null} or blank
   * @return the usable name, never empty
   */
  private static @NotNull String safeName(@Nullable String name) {
    if (name == null) {
      return FALLBACK_NAME;
    }
    String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\p{Cc}]", "_").replaceAll("\\s+", " ").strip();
    if (cleaned.length() > MAX_NAME_LENGTH) {
      cleaned = cleaned.substring(0, MAX_NAME_LENGTH).strip();
    }
    cleaned = cleaned.replaceAll("[. ]+$", "");
    return cleaned.isEmpty() ? FALLBACK_NAME : cleaned;
  }
}
