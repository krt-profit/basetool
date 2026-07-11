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
 * Parses an uploaded ship-export file into the format-agnostic {@link FleetImportEntry} stream that
 * {@code HangarImportService} then resolves and imports. Extracted verbatim from that service
 * (audit L-tier import-engine split, #16) so it owns the format sniffing and per-format DTO mapping
 * — CCU Game Fleetview, HangarXPLOR Shiplist, Fleetyards and StarJump FleetViewer — while the
 * service keeps the transactional import orchestration.
 *
 * <p>Format detection (see {@link #parse}): an object root with a {@code starjumpFleetviewer} type
 * or a {@code canvasItems} array is StarJump FleetViewer; an array root is probed by field name
 * ({@code pledge_id}/{@code ship_code} → HangarXPLOR, {@code shipname}/{@code type} → Fleetview,
 * {@code shipCode}/{@code manufacturerCode} → Fleetyards). Records with a blank name, a non-ship
 * {@code entity_type} (Shiplist) or a non-{@code SHIP} {@code itemType} (FleetViewer) are dropped
 * before they reach the resolver.
 *
 * <p>Stateless and static-only; it takes the caller's {@link ObjectMapper} as a parameter rather
 * than injecting one, so {@code HangarImportService} passes its own configured mapper. The
 * 8&nbsp;MB pre-{@code readTree} size cap ({@link #MAX_IMPORT_BYTES}) is the security backstop
 * against a multi-MB array expanding into hundreds of MB of transient heap; it lives here with the
 * parse.
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

  /**
   * Application-level cap on a hangar/fleet upload, enforced before the body is materialised into a
   * Jackson tree (security audit gap-fill). A real ship-list export is well under 1 MB; 8 MB leaves
   * generous headroom for very large fleets while keeping this member-reachable import off the 64
   * MB global multipart cap (sized for the admin-only P4K catalogue) — a flat multi-MB array would
   * otherwise expand into hundreds of MB of transient heap per request.
   */
  private static final long MAX_IMPORT_BYTES = 8L * 1024 * 1024;

  private FleetExportParser() {}

  /**
   * Reads the multipart body, sniffs the format from the payload shape, and converts every record
   * into the format-agnostic {@link FleetImportEntry}. A JSON <em>object</em> root with a {@code
   * starjumpFleetviewer} {@code type} or a {@code canvasItems} array is parsed as StarJump
   * FleetViewer; a JSON <em>array</em> root is probed by field name: a {@code pledge_id} or {@code
   * ship_code} key flags HangarXPLOR Shiplist; a {@code shipname} or {@code type} key flags CCU
   * Game Fleetview; a {@code shipCode} or {@code manufacturerCode} key flags Fleetyards (camelCase,
   * so it never collides with HangarXPLOR's snake_case {@code ship_code}). Records with a blank
   * {@code name}/{@code defaultText}, a non-{@code "ship"} {@code entity_type} (Shiplist) or a
   * non-{@code "SHIP"} {@code itemType} (FleetViewer) are silently dropped — they never reach the
   * resolver.
   *
   * @param objectMapper the caller's configured JSON mapper
   * @param file multipart upload from the controller
   * @return parsed entries (possibly empty); never {@code null}
   * @throws BadRequestException if the file is too large, the JSON is malformed, the root is
   *     neither an array nor a recognised FleetViewer object, or no field on the first array
   *     element matches a known format
   */
  public static @NotNull List<FleetImportEntry> parse(
      @NotNull ObjectMapper objectMapper, @NotNull MultipartFile file) {
    // Reject an oversized upload BEFORE readTree builds the in-memory tree (security audit
    // gap-fill). getSize() reflects the buffered multipart length, so this never reads the body.
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
      // IOException covers the multipart stream; JacksonException (unchecked in Jackson 3) covers a
      // malformed JSON body — both must surface as a 400, not bubble up as a 500.
      log.debug("Hangar import: failed to parse JSON", e);
      throw new BadRequestException(
          "The uploaded file could not be parsed as a valid ship-list JSON.");
    }

    // StarJump FleetViewer exports use an object root with a canvasItems array, unlike the two
    // array-based formats — branch on it before the array contract below.
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
   * Recognises a StarJump FleetViewer ("Hangar Link") export from its object root. The
   * authoritative marker is the {@code "type":"starjumpFleetviewer"} discriminator (matched
   * case-insensitively); a top-level {@code canvasItems} array is accepted as a secondary signal so
   * a future export that drops or renames the {@code type} field still resolves. Because the two
   * array formats can never present as an object, the presence of {@code canvasItems} on an object
   * root is unambiguous.
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
   * Parses a StarJump FleetViewer object root into the format-agnostic entry stream. Only {@code
   * SHIP} canvas items survive {@link #mapStarjumpEntry(StarjumpCanvasItemDto)}; decorative {@code
   * TEXTGROUP} labels and any item without a usable name/slug are dropped. A {@code null} or absent
   * {@code canvasItems} array yields an empty list rather than an error so an empty FleetViewer
   * canvas imports as a no-op.
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
   * Lifts a StarJump FleetViewer canvas item into the internal representation. Non-{@code "SHIP"}
   * items (decorative {@code TEXTGROUP} labels, widgets) are dropped. The {@code defaultText}
   * becomes the resolvable name; if it is blank the kebab-case {@code shipSlug} is used as the name
   * fallback so a tile with only a slug can still be matched. The trimmed {@code shipSlug} is
   * always carried separately to drive the slug-fallback match stage. FleetViewer exports no custom
   * ship name and no insurance, so both are left {@code null} (insurance falls back to {@link
   * #DEFAULT_INSURANCE}).
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
   * Lifts a HangarXPLOR Shiplist record into the internal representation. Non-{@code "ship"} entity
   * types are dropped. The individual-name heuristic discards a {@code ship_name} that is just an
   * abbreviation/echo of {@code name} (e.g. {@code ship_name="325a"} for {@code name="325a
   * Fighter"}), so only truly custom names like {@code "KRT Olymp"} survive. {@code lti=true} maps
   * to insurance {@code "LTI"}; {@code lti=false} or absent leaves insurance {@code null} so the
   * caller falls back to {@link #DEFAULT_INSURANCE}.
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
   * Lifts a Fleetyards record into the internal representation. The {@code name} is the resolvable
   * model name; {@code shipName} becomes the individual name only when it is a genuine custom name
   * (not an echo/abbreviation of {@code name}), reusing the same heuristic as the Shiplist mapper;
   * and the manufacturer-prefixed {@code slug} is carried verbatim to drive the slug-fallback match
   * stage. Fleetyards exports no insurance information, so insurance is left {@code null} and the
   * caller falls back to {@link #DEFAULT_INSURANCE}.
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
   * Decides whether a source-provided custom name is worth preserving on the imported ship or is
   * just an abbreviation/echo of the model name. Shared by the Shiplist mapper (HangarXPLOR {@code
   * ship_name}) and the Fleetyards mapper ({@code shipName}). The check uses the same alphanumeric
   * normalisation as the matcher ({@link ShipTypeMatcher#normalizeForMatching}): if the normalised
   * custom name is a substring of the normalised model name, treat it as an echo and return {@code
   * null}. Empirically (n=64 in the real shiplist) this keeps the three actual custom names ({@code
   * "KRT Olymp"}, {@code "KRT Falcon"}, {@code "KRT Franklin"}) and discards 12 model-name echoes
   * like {@code "325a"} → {@code "325a Fighter"} or {@code "Caterpillar"} → {@code "Caterpillar
   * Pirate Edition"}.
   *
   * @param modelName value of the source {@code name} field
   * @param customName value of the source custom-name field (Shiplist {@code ship_name} /
   *     Fleetyards {@code shipName}), nullable
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
