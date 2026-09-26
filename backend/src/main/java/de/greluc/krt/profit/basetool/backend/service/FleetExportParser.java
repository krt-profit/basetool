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
import de.greluc.krt.profit.basetool.backend.model.dto.FleetviewEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.FleetyardsEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ShiplistEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.StarjumpCanvasItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.StarjumpFleetviewerDto;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses an uploaded ship-export file (CCU Game Fleetview, HangarXPLOR Shiplist, Fleetyards,
 * StarJump FleetViewer) into the format-agnostic {@link FleetImportEntry} stream that {@code
 * HangarImportService} imports.
 *
 * <p>Stateless; takes the caller's {@link ObjectMapper}. Uploads above {@link #MAX_IMPORT_BYTES}
 * are rejected before parsing.
 */
@Slf4j
public final class FleetExportParser {

  /**
   * Neutral insurance default used for imported ships when no explicit insurance information is
   * carried by the upload (Fleetview always, Shiplist when {@code lti=false} or absent). Applied by
   * {@code HangarImportService} when an entry's {@link FleetImportEntry#insurance()} is {@code
   * null}.
   */
  public static final String DEFAULT_INSURANCE = "0";

  /**
   * Insurance string set on imported ships when the HangarXPLOR record carries {@code lti=true}.
   */
  public static final String LTI_INSURANCE = "LTI";

  /** Maximum upload size in bytes (8 MB), enforced before the body is read into a Jackson tree. */
  private static final long MAX_IMPORT_BYTES = 8L * 1024 * 1024;

  /**
   * Cap on the number of entries one ship-list import may carry, applied after parsing.
   *
   * <p>Sibling of {@code BlueprintExportParser#MAX_IMPORT_ENTRIES} and there for the same reason:
   * the byte cap bounds the JSON tree, not the per-entry database work the importer then performs.
   */
  private static final int MAX_IMPORT_ENTRIES = 20_000;

  private FleetExportParser() {}

  /**
   * Reads the upload, detects its format from the payload shape and converts every ship record into
   * a {@link FleetImportEntry}.
   *
   * <p>An object root with a {@code starjumpFleetviewer} type or a {@code canvasItems} array is
   * StarJump FleetViewer; an array root is probed by field name ({@code pledge_id}/{@code
   * ship_code} HangarXPLOR, {@code shipname}/{@code type} Fleetview, {@code shipCode}/{@code
   * manufacturerCode} Fleetyards). Records without a name or that are not ships are dropped.
   *
   * @param objectMapper the caller's configured JSON mapper
   * @param file multipart upload from the controller
   * @return parsed entries (possibly empty); never {@code null}
   * @throws BadRequestException if the file is too large, malformed, or of no recognised format
   */
  public static @NotNull List<FleetImportEntry> parse(
      @NotNull ObjectMapper objectMapper, @NotNull MultipartFile file) {
    List<FleetImportEntry> entries = parseEntries(objectMapper, file);
    if (entries.size() > MAX_IMPORT_ENTRIES) {
      throw new BadRequestException(
          "The uploaded ship list carries "
              + entries.size()
              + " entries; at most "
              + MAX_IMPORT_ENTRIES
              + " are accepted per import.");
    }
    return entries;
  }

  /**
   * The format-detecting parse itself, without the entry-count cap {@link #parse} applies on top.
   *
   * @param objectMapper the caller's configured JSON mapper
   * @param file the uploaded ship-list JSON
   * @return the parsed entries, unbounded in count
   */
  private static @NotNull List<FleetImportEntry> parseEntries(
      @NotNull ObjectMapper objectMapper, @NotNull MultipartFile file) {
    if (file.getSize() > MAX_IMPORT_BYTES) {
      throw new BadRequestException(
          "The uploaded ship-list file is too large (limit "
              + (MAX_IMPORT_BYTES / (1024 * 1024))
              + " MB).");
    }
    JsonNode root;
    try {
      root = objectMapper.readTree(file.getInputStream());
    } catch (IOException | JacksonException e) {
      log.debug("Hangar import: failed to parse JSON", e);
      throw new BadRequestException(
          "The uploaded file could not be parsed as a valid ship-list JSON.");
    }

    if (root != null && root.isObject() && isStarjumpFleetviewer(root)) {
      return parseStarjumpEntries(objectMapper, root);
    }

    if (root == null || !root.isArray()) {
      throw new BadRequestException("The uploaded file must contain a JSON array at the root.");
    }
    if (root.isEmpty()) {
      return List.of();
    }

    JsonNode probe = null;
    for (JsonNode el : root) {
      if (el != null && el.isObject()) {
        probe = el;
        break;
      }
    }
    if (probe == null) {
      throw new BadRequestException("The uploaded file does not contain any objects to import.");
    }

    if (probe.has("pledge_id") || probe.has("ship_code")) {
      List<ShiplistEntryDto> raw =
          objectMapper.convertValue(root, new TypeReference<List<ShiplistEntryDto>>() {});
      return raw.stream()
          .map(FleetExportParser::mapShiplistEntry)
          .filter(Objects::nonNull)
          .toList();
    }
    if (probe.has("shipname") || probe.has("type")) {
      List<FleetviewEntryDto> raw =
          objectMapper.convertValue(root, new TypeReference<List<FleetviewEntryDto>>() {});
      return raw.stream()
          .map(FleetExportParser::mapFleetviewEntry)
          .filter(Objects::nonNull)
          .toList();
    }
    if (probe.has("shipCode") || probe.has("manufacturerCode")) {
      List<FleetyardsEntryDto> raw =
          objectMapper.convertValue(root, new TypeReference<List<FleetyardsEntryDto>>() {});
      return raw.stream()
          .map(FleetExportParser::mapFleetyardsEntry)
          .filter(Objects::nonNull)
          .toList();
    }
    throw new BadRequestException(
        "Unknown ship-list format. Expected CCU Game Fleetview, HangarXPLOR Shiplist or Fleetyards"
            + " JSON.");
  }

  /**
   * Recognises a StarJump FleetViewer export by its case-insensitive {@code "type":
   * "starjumpFleetviewer"} discriminator or a top-level {@code canvasItems} array.
   *
   * @param root the parsed JSON object root
   * @return {@code true} iff the payload should be parsed as StarJump FleetViewer
   */
  private static boolean isStarjumpFleetviewer(@NotNull JsonNode root) {
    JsonNode type = root.get("type");
    if (type != null
        && type.isString()
        && "starjumpfleetviewer".equals(type.asString().toLowerCase(Locale.ROOT))) {
      return true;
    }
    return root.has("canvasItems");
  }

  /**
   * Parses a StarJump FleetViewer object root into entries, keeping only ship canvas items. A
   * missing {@code canvasItems} array yields an empty list.
   *
   * @param objectMapper the caller's configured JSON mapper
   * @param root the parsed JSON object root (already confirmed as FleetViewer)
   * @return parsed entries (possibly empty); never {@code null}
   */
  private static @NotNull List<FleetImportEntry> parseStarjumpEntries(
      @NotNull ObjectMapper objectMapper, @NotNull JsonNode root) {
    StarjumpFleetviewerDto dto = objectMapper.convertValue(root, StarjumpFleetviewerDto.class);
    if (dto == null || dto.canvasItems() == null) {
      return List.of();
    }
    return dto.canvasItems().stream()
        .map(FleetExportParser::mapStarjumpEntry)
        .filter(Objects::nonNull)
        .toList();
  }

  /**
   * Maps a StarJump FleetViewer canvas item to an entry, dropping non-{@code "SHIP"} items. The
   * {@code defaultText} is the name, falling back to {@code shipSlug}; the slug is always carried
   * and insurance falls back to {@link #DEFAULT_INSURANCE}.
   *
   * @param item raw FleetViewer canvas item
   * @return internal entry, or {@code null} if the item is not a ship or has neither name nor slug
   */
  private static @Nullable FleetImportEntry mapStarjumpEntry(@Nullable StarjumpCanvasItemDto item) {
    if (item == null || item.itemType() == null || !"ship".equalsIgnoreCase(item.itemType())) {
      return null;
    }
    String slug =
        (item.shipSlug() != null && !item.shipSlug().isBlank()) ? item.shipSlug().trim() : null;
    String name =
        (item.defaultText() != null && !item.defaultText().isBlank())
            ? item.defaultText().trim()
            : slug;
    if (name == null) {
      return null;
    }
    return new FleetImportEntry(name, null, null, slug);
  }

  /**
   * Lifts a Fleetview record into the internal representation. The shipname becomes the individual
   * name when non-blank; insurance is always left {@code null} because Fleetview carries no
   * insurance information.
   *
   * @param dto raw Fleetview entry
   * @return internal entry, or {@code null} if the model name is blank
   */
  private static @Nullable FleetImportEntry mapFleetviewEntry(@Nullable FleetviewEntryDto dto) {
    if (dto == null || dto.name() == null || dto.name().isBlank()) {
      return null;
    }
    String individual =
        (dto.shipname() != null && !dto.shipname().isBlank()) ? dto.shipname().trim() : null;
    return new FleetImportEntry(dto.name(), individual, null, null);
  }

  /**
   * Maps a HangarXPLOR Shiplist record to an entry, dropping non-{@code "ship"} entity types. A
   * {@code ship_name} that merely echoes the model name is discarded; {@code lti=true} maps to
   * insurance {@code "LTI"}, otherwise insurance is {@code null}.
   *
   * @param dto raw HangarXPLOR entry
   * @return internal entry, or {@code null} if the entity type or model name make it ineligible
   */
  private static @Nullable FleetImportEntry mapShiplistEntry(@Nullable ShiplistEntryDto dto) {
    if (dto == null || dto.name() == null || dto.name().isBlank()) {
      return null;
    }
    if (dto.entityType() != null && !"ship".equalsIgnoreCase(dto.entityType())) {
      return null;
    }
    String individual = computeCustomShipName(dto.name(), dto.shipName());
    String insurance = Boolean.TRUE.equals(dto.lti()) ? LTI_INSURANCE : null;
    return new FleetImportEntry(dto.name(), individual, insurance, null);
  }

  /**
   * Maps a Fleetyards record to an entry: {@code name} is the model name, {@code shipName} the
   * custom name unless it echoes the model, and {@code slug} is carried for slug matching.
   * Insurance is left {@code null}.
   *
   * @param dto raw Fleetyards entry
   * @return internal entry, or {@code null} if the model name is blank
   */
  private static @Nullable FleetImportEntry mapFleetyardsEntry(@Nullable FleetyardsEntryDto dto) {
    if (dto == null || dto.name() == null || dto.name().isBlank()) {
      return null;
    }
    String individual = computeCustomShipName(dto.name(), dto.shipName());
    String slug = (dto.slug() != null && !dto.slug().isBlank()) ? dto.slug().trim() : null;
    return new FleetImportEntry(dto.name(), individual, null, slug);
  }

  /**
   * Returns the custom ship name unless it is blank or, after {@link
   * ShipTypeMatcher#normalizeForMatching} normalisation, a substring of the model name.
   *
   * @param modelName value of the source {@code name} field
   * @param customName value of the source custom-name field, nullable
   * @return the trimmed custom name, or {@code null} if it is an echo or blank
   */
  private static @Nullable String computeCustomShipName(
      @NotNull String modelName, @Nullable String customName) {
    if (customName == null || customName.isBlank()) {
      return null;
    }
    String normModel = ShipTypeMatcher.normalizeForMatching(modelName);
    String normShip = ShipTypeMatcher.normalizeForMatching(customName);
    if (normShip.isEmpty() || normModel.contains(normShip)) {
      return null;
    }
    return customName.trim();
  }

  /**
   * Format-agnostic representation of a single upload entry after Fleetview / Shiplist / Fleetyards
   * / FleetViewer mapping. Used so the resolver does not have to branch on the source format.
   *
   * @param name the ship-model name to resolve against {@code ShipType.name}
   * @param individualName custom display name for the ship, or {@code null}
   * @param insurance explicit insurance string (e.g. {@code "LTI"}), or {@code null} to let the
   *     service fall back to {@link #DEFAULT_INSURANCE}
   * @param slug source-provided ship slug used for the slug-fallback match stage (StarJump
   *     FleetViewer and Fleetyards), or {@code null} for formats that carry no slug
   */
  public record FleetImportEntry(
      @NotNull String name,
      @Nullable String individualName,
      @Nullable String insurance,
      @Nullable String slug) {}
}
