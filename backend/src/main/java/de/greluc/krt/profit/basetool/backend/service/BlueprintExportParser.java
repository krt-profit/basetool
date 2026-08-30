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
 * Parses an uploaded SCMDB blueprint export into the de-duplicated {@link ParsedEntry} stream that
 * {@code BlueprintImportService} then resolves against the master product list. Extracted verbatim
 * from that service (audit L-tier import-engine split, #16) so it owns the JSON shape handling, the
 * tag-vs-name de-duplication and the multi-exporter timestamp coercion, while the service keeps the
 * resolution chain, owned-flag logic and persistence.
 *
 * <p>Accepts either the documented {@code {"blueprints": [...]}} object (SCMDB log-watcher,
 * Basetool Blueprint Extractor, scmdb.net profile / tracking export — REQ-INV-014) or a bare array.
 * Entries collapse by their structural {@code tag} when present, else by trimmed product name,
 * keeping the earliest acquisition time; scmdb.net checklist rows not yet unlocked ({@code
 * completed == false}) are skipped and blank names dropped. Acquisition time is coerced from
 * whichever field the source stamped: SCMDB {@code ts} (fractional epoch seconds) first, then the
 * Extractor's ISO-8601 {@code receivedAt} (a malformed value is treated as absent, never failing
 * the import).
 *
 * <p>Stateless and static-only; it takes the caller's {@link ObjectMapper} as a parameter rather
 * than injecting one, so {@code BlueprintImportService} passes its own configured mapper. The
 * 8&nbsp;MB pre-{@code readTree} size cap ({@link #MAX_IMPORT_BYTES}) bounds the transient heap a
 * multi-MB array expands into; the per-entry work downstream is bounded separately by {@link
 * #MAX_IMPORT_ENTRIES}, because de-dup keys on the name and therefore does not bound the entry
 * count at all.
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
   * Cap on the number of <em>distinct</em> entries one import may carry, enforced after de-dup.
   *
   * <p>The byte cap above is not the backstop its javadoc claimed. De-duplication keys on the name
   * (or tag), so every distinct name survives it: an 8&nbsp;MiB upload of ~14-byte minimal records
   * yields on the order of half a million entries, and {@code BlueprintImportService} then runs one
   * alias lookup plus one full-catalogue fuzzy scan <em>per entry</em> - inside a single
   * {@code @Transactional(readOnly = true)}, so one request also parks one Hikari connection for
   * the whole run. Enough concurrent requests exhaust the pool for everybody, from any
   * authenticated account.
   *
   * <p>A real export is in the hundreds; 20&nbsp;000 leaves three orders of magnitude of headroom
   * over the largest plausible library while removing the unbounded loop.
   */
  private static final int MAX_IMPORT_ENTRIES = 20_000;

  private BlueprintExportParser() {}

  /**
   * Reads the multipart body and converts it into the de-duplicated parsed entries. Accepts either
   * the documented {@code {"blueprints": [...]}} object (the SCMDB log-watcher, the Basetool
   * Blueprint Extractor, and the scmdb.net profile / tracking export all wrap their records this
   * way — REQ-INV-014) or a bare array of blueprint records. The scmdb.net {@code name} key is read
   * as {@code productName} (via {@code @JsonAlias}); scmdb.net checklist entries the user has not
   * unlocked yet ({@code completed == false}) are skipped, while a {@code null} / {@code true} flag
   * (the watcher / extractor exports, which list only acquired blueprints) counts as owned. Entries
   * collapse by their structural {@code tag} when present, else by trimmed product name, keeping
   * the earliest acquisition time as the suggestion — so two distinct blueprints scmdb.net shows
   * under one name (different tags) stay separate while tag-less duplicates merge as before; blank
   * names are dropped.
   *
   * @param objectMapper the caller's configured JSON mapper
   * @param file the uploaded blueprint export JSON
   * @return parsed entries in first-seen order (possibly empty)
   * @throws BadRequestException if the file is empty, too large, not valid JSON, or carries no
   *     blueprint array
   */
  public static @NotNull List<ParsedEntry> parse(
      @NotNull ObjectMapper objectMapper, @NotNull MultipartFile file) {
    if (file.isEmpty()) {
      throw new BadRequestException("The uploaded file is empty.");
    }
    // Reject an oversized upload BEFORE readTree builds the in-memory tree (security audit
    // gap-fill). getSize() reflects the buffered multipart length, so this never reads the body.
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

    // Collapse duplicates, keeping the earliest acquisition time per group. The de-dup key is the
    // structural tag (lower-cased) when present, else the trimmed product name. Keying on the tag
    // is what stops two DISTINCT DataForge blueprints that scmdb.net happens to display under the
    // same name — e.g. a genuine piece and a CIG-mislabeled one both shown as "Antium Core Jet"
    // (REQ-INV-007) — from collapsing into one (which would drop one tag and import only one of the
    // two owned products). Tag-less entries (watcher / extractor / bare array) key on the name, so
    // their de-dup behaviour is unchanged. scmdb.net checklist entries the user has not unlocked
    // yet
    // (completed == false) are skipped.
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
      String tag = entry.tag() == null || entry.tag().isBlank() ? null : entry.tag().trim();
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
   * Resolves a parsed entry's acquisition instant from whichever timestamp its source exporter
   * stamped: SCMDB's {@code ts} (fractional Unix epoch seconds) takes precedence, then the Basetool
   * Blueprint Extractor's {@code receivedAt} (ISO-8601 instant). A malformed {@code receivedAt} is
   * treated as absent rather than failing the whole import.
   *
   * @param entry the parsed export entry
   * @return the acquisition instant, or {@code null} if neither field is present and parseable
   */
  private static @Nullable Instant acquiredAtOf(@NotNull BlueprintExportEntryDto entry) {
    if (entry.ts() != null) {
      return toInstant(entry.ts());
    }
    return parseInstant(entry.receivedAt());
  }

  /**
   * Parses an ISO-8601 instant string (e.g. {@code 2026-03-26T16:49:31.050Z}) leniently. A blank or
   * unparseable value yields {@code null} so one malformed record never aborts the import.
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
   * A single de-duplicated export entry after parsing: the external product name, the structural
   * blueprint tag (scmdb.net only), and the earliest acquisition instant seen for it.
   *
   * @param externalName the export {@code productName} / scmdb.net {@code name} (trimmed)
   * @param tag the scmdb.net structural blueprint key for the tag match (REQ-INV-019), or {@code
   *     null} for the watcher / extractor exports, which do not carry it
   * @param suggestedAcquiredAt the earliest acquisition instant (from {@code ts} or {@code
   *     receivedAt}), or {@code null}
   */
  public record ParsedEntry(
      @NotNull String externalName, @Nullable String tag, @Nullable Instant suggestedAcquiredAt) {}
}
