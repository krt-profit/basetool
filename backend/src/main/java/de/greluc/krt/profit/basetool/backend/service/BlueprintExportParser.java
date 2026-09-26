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
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintExportEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintExportFileDto;
import de.greluc.krt.profit.basetool.backend.support.StringNormalization;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses an uploaded blueprint export into de-duplicated {@link ParsedEntry} records (REQ-INV-014).
 *
 * <p>Accepts a {@code {"blueprints": [...]}} object or a bare array. The upload is capped at {@link
 * #MAX_IMPORT_BYTES} and {@link #MAX_IMPORT_ENTRIES} distinct entries.
 */
@Slf4j
public final class BlueprintExportParser {

  /**
   * Application-level cap on a blueprint-export upload, enforced before the body is materialised
   * into a Jackson tree (security audit gap-fill). A real blueprint export is well under 1 MB; 8 MB
   * leaves generous headroom while keeping this member-reachable import off the 64 MB global
   * multipart cap (sized for the admin-only P4K catalogue).
   */
  private static final long MAX_IMPORT_BYTES = 8L * 1024 * 1024;

  /**
   * Maximum number of distinct entries per import, enforced after de-duplication, which bounds the
   * per-entry resolution work of one request.
   */
  private static final int MAX_IMPORT_ENTRIES = 20_000;

  private BlueprintExportParser() {}

  /**
   * Parses the upload into de-duplicated entries (REQ-INV-014).
   *
   * <p>Entries collapse by {@code tag} when present, else by trimmed name, keeping the earliest
   * acquisition time; entries with {@code completed == false} and blank names are dropped.
   *
   * @param objectMapper the caller's JSON mapper
   * @param file the uploaded blueprint export JSON
   * @return parsed entries in first-seen order, possibly empty
   * @throws BadRequestException if the file is empty, too large, not valid JSON, or carries no
   *     blueprint array
   */
  public static @NotNull List<ParsedEntry> parse(
      @NotNull ObjectMapper objectMapper, @NotNull MultipartFile file) {
    if (file.isEmpty()) {
      throw new BadRequestException("The uploaded file is empty.");
    }
    if (file.getSize() > MAX_IMPORT_BYTES) {
      throw new BadRequestException(
          "The uploaded blueprint file is too large (limit "
              + (MAX_IMPORT_BYTES / (1024 * 1024))
              + " MB).");
    }
    JsonNode root;
    try {
      root = objectMapper.readTree(file.getInputStream());
    } catch (IOException | JacksonException e) {
      log.warn("Blueprint import: failed to parse JSON — {}", e.getMessage());
      throw new BadRequestException(
          "The uploaded file could not be parsed as valid blueprint export JSON.");
    }

    List<BlueprintExportEntryDto> raw;
    if (root != null && root.isArray()) {
      raw = objectMapper.convertValue(root, new TypeReference<List<BlueprintExportEntryDto>>() {});
    } else if (root != null && root.isObject()) {
      raw = objectMapper.convertValue(root, BlueprintExportFileDto.class).blueprints();
    } else {
      raw = null;
    }
    if (raw == null) {
      throw new BadRequestException(
          "The uploaded file must contain a 'blueprints' array (SCMDB log-watcher, Basetool"
              + " Blueprint Extractor, or scmdb.net export).");
    }

    LinkedHashMap<String, Instant> earliestByKey = new LinkedHashMap<>();
    LinkedHashMap<String, String> nameByKey = new LinkedHashMap<>();
    LinkedHashMap<String, String> tagByKey = new LinkedHashMap<>();
    for (BlueprintExportEntryDto entry : raw) {
      if (entry == null || entry.productName() == null || entry.productName().isBlank()) {
        continue;
      }
      if (Boolean.FALSE.equals(entry.completed())) {
        continue;
      }
      String name = entry.productName().trim();
      String tag = StringNormalization.trimToNull(entry.tag());
      Instant acquiredAt = acquiredAtOf(entry);
      String dedupKey = tag != null ? "t:" + tag.toLowerCase(Locale.ROOT) : "n:" + name;
      if (!earliestByKey.containsKey(dedupKey)) {
        earliestByKey.put(dedupKey, acquiredAt);
        nameByKey.put(dedupKey, name);
        tagByKey.put(dedupKey, tag);
      } else {
        Instant current = earliestByKey.get(dedupKey);
        if (acquiredAt != null && (current == null || acquiredAt.isBefore(current))) {
          earliestByKey.put(dedupKey, acquiredAt);
        }
      }
    }

    if (earliestByKey.size() > MAX_IMPORT_ENTRIES) {
      throw new BadRequestException(
          "The blueprint export carries "
              + earliestByKey.size()
              + " distinct entries; at most "
              + MAX_IMPORT_ENTRIES
              + " are accepted per import.");
    }

    List<ParsedEntry> entries = new ArrayList<>(earliestByKey.size());
    for (String key : earliestByKey.keySet()) {
      entries.add(new ParsedEntry(nameByKey.get(key), tagByKey.get(key), earliestByKey.get(key)));
    }
    return entries;
  }

  /**
   * Resolves an entry's acquisition instant from {@code ts} (epoch seconds) or, failing that,
   * {@code receivedAt} (ISO-8601); a malformed value counts as absent.
   *
   * @param entry the parsed export entry
   * @return the acquisition instant, or {@code null}
   */
  private static @Nullable Instant acquiredAtOf(@NotNull BlueprintExportEntryDto entry) {
    if (entry.ts() != null) {
      return toInstant(entry.ts());
    }
    return parseInstant(entry.receivedAt());
  }

  /**
   * Parses an ISO-8601 instant leniently; a blank or unparseable value yields {@code null}.
   *
   * @param iso the ISO-8601 instant string, or {@code null}
   * @return the parsed instant, or {@code null}
   */
  private static @Nullable Instant parseInstant(@Nullable String iso) {
    if (iso == null || iso.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(iso.trim());
    } catch (DateTimeParseException e) {
      log.debug("Blueprint import: ignoring unparseable receivedAt '{}'", iso);
      return null;
    }
  }

  /**
   * Converts a fractional Unix-epoch-seconds timestamp into an {@link Instant} (millisecond
   * precision). {@code null} in yields {@code null} out.
   *
   * @param epochSeconds fractional epoch seconds (e.g. {@code 1774534484.296}), or {@code null}
   * @return the corresponding instant, or {@code null}
   */
  private static @Nullable Instant toInstant(@Nullable Double epochSeconds) {
    return epochSeconds == null ? null : Instant.ofEpochMilli(Math.round(epochSeconds * 1000.0));
  }

  /**
   * A de-duplicated export entry: external product name, structural tag and earliest acquisition
   * instant.
   *
   * @param externalName the trimmed external product name
   * @param tag the scmdb.net structural blueprint key (REQ-INV-019), or {@code null}
   * @param suggestedAcquiredAt the earliest acquisition instant, or {@code null}
   */
  public record ParsedEntry(
      @NotNull String externalName, @Nullable String tag, @Nullable Instant suggestedAcquiredAt) {}
}
