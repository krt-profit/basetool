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
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.FleetviewEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.FleetviewImportResponseDto;
import de.greluc.krt.profit.basetool.backend.model.dto.FleetyardsEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ShiplistEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.StarjumpCanvasItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.StarjumpFleetviewerDto;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Handles the import of a third-party ship-export JSON into a user's hangar.
 *
 * <p>Four upload formats are accepted and auto-detected from the payload shape:
 *
 * <ul>
 *   <li><strong>CCU Game Fleetview</strong> — a flat array of {@code {"name", "shipname", "type"}}
 *       records. No insurance data is carried, so imported ships fall back to the neutral {@link
 *       #DEFAULT_INSURANCE} value.
 *   <li><strong>HangarXPLOR Shiplist</strong> (see {@code https://github.com/dolkensp/HangarXPLOR})
 *       — a richer array of pledge-aware records. Only {@code name}, {@code ship_name}, {@code
 *       entity_type} and {@code lti} are consumed. {@code lti=true} populates the imported ship's
 *       insurance with {@code "LTI"}; {@code lti=false} falls back to {@link #DEFAULT_INSURANCE}
 *       (we know it is not lifetime, but we do not know the actual month count). Entries with
 *       {@code entity_type} other than {@code "ship"} are skipped defensively so that HangarXPLOR
 *       module/package rows never become ships.
 *   <li><strong>Fleetyards</strong> (see {@code https://fleetyards.net}) — a flat array of vehicle
 *       records keyed by a {@code shipCode}/{@code manufacturerCode} pair (camelCase, distinct from
 *       HangarXPLOR's snake_case {@code ship_code}). Only {@code name}, {@code shipName} and {@code
 *       slug} are consumed: {@code name} drives the name match, {@code shipName} becomes the
 *       imported ship's individual name when it is a genuine custom name (not an echo of {@code
 *       name}), and the manufacturer-prefixed kebab-case {@code slug} (e.g. {@code "rsi-galaxy"},
 *       whose shape mirrors the SC Wiki slug rather than UEX's bare slug) drives the slug-fallback
 *       match stage. No insurance data is carried, so imported ships fall back to {@link
 *       #DEFAULT_INSURANCE}.
 *   <li><strong>StarJump FleetViewer</strong> ("Hangar Link", from {@code
 *       https://fleetviewer.link}) — a JSON <em>object</em> (not an array) with a {@code
 *       "type":"starjumpFleetviewer"} discriminator and a {@code canvasItems} array. Only items
 *       with {@code "itemType":"SHIP"} are imported; decorative {@code TEXTGROUP} labels and other
 *       widgets are skipped. The {@code defaultText} field supplies the resolvable ship name and
 *       the kebab-case {@code shipSlug} drives the slug-fallback match stage. No insurance data is
 *       carried, so imported ships fall back to {@link #DEFAULT_INSURANCE}.
 * </ul>
 *
 * <p>The three array formats are sniffed from the first array element (field names, not values: a
 * {@code pledge_id}/{@code ship_code} key flags HangarXPLOR, a {@code shipname}/{@code type} key
 * flags Fleetview, a {@code shipCode}/{@code manufacturerCode} key flags Fleetyards); an object
 * root with a {@code starjumpFleetviewer} type or a {@code canvasItems} array flags StarJump
 * FleetViewer. After format-specific parsing all four payloads are unified into a single internal
 * {@link FleetImportEntry} stream, and the rest of the import is format-agnostic.
 *
 * <p>Each entry is then resolved against existing {@code ShipType} rows through a four-stage
 * tolerant lookup that maximises matches against UEX's canonical naming (the actual content of the
 * {@code ship_type} table is whatever {@code UexVehicleService} last pulled from {@code
 * https://api.uexcorp.space/2.0/vehicles}):
 *
 * <ol>
 *   <li><strong>Exact case-insensitive match</strong> on {@code ShipType.name} (e.g. {@code "L-21
 *       Wolf"} matches {@code "L-21 Wolf"} regardless of casing).
 *   <li><strong>Normalised match</strong> — both names are folded to lowercase and stripped of
 *       every non-alphanumeric character before comparison. Absorbs hyphen/whitespace drift such as
 *       Fleetview {@code "L21 Wolf"} ↔ UEX {@code "L-21 Wolf"}, {@code "Cyclone-AA"} ↔ UEX {@code
 *       "Cyclone AA"} and HangarXPLOR's {@code "A.T.L.S."} ↔ UEX {@code "ATLS"}.
 *   <li><strong>fv-tokens ⊆ uex-tokens, uniquely</strong> — the entry is a strict abbreviation of a
 *       UEX vehicle (UEX adds marketing-style suffixes that the export omits, like {@code
 *       "Starlifter"}, {@code "Starfighter"}, {@code "Star Runner"}, {@code "Tank"}, {@code
 *       "Rescue"} or the {@code "Mk I"} default-variant tag). Token-<em>set</em> comparison absorbs
 *       word-order drift such as HangarXPLOR's {@code "Hercules Starlifter A2"} ↔ UEX {@code "A2
 *       Hercules Starlifter"}. If the fv-token-set matches more than one UEX vehicle (e.g. {@code
 *       "F7C-M Super Hornet"} would match {@code "Mk I"} / {@code "Heartseeker Mk I"} / {@code "Mk
 *       II"}), the entry is left unresolved to avoid guessing.
 *   <li><strong>uex-tokens ⊆ fv-tokens, uniquely</strong> — the reverse direction, for cases where
 *       the export uses a longer marketing name than UEX's canonical short name (e.g. {@code "Ursa
 *       Rover"} → UEX {@code "Ursa"}, {@code "325a Fighter"} → UEX {@code "325a"}).
 *   <li><strong>Slug fallback</strong> (StarJump FleetViewer and Fleetyards only) — when all four
 *       name stages miss (or are left ambiguous), the source-provided slug ({@code shipSlug} for
 *       FleetViewer, {@code slug} for Fleetyards) is folded with the same alphanumeric
 *       normalisation and matched against {@code ShipType.uexSlug} then {@code
 *       ShipType.scwikiSlug}. These slug schemes diverge from UEX's just enough that this cannot be
 *       the primary key (e.g. FleetViewer {@code "zeus-mkii-mr"} vs UEX {@code "zeus-mk-ii-mr"};
 *       Fleetyards prefixes the manufacturer, e.g. {@code "rsi-galaxy"}, which lines up with the SC
 *       Wiki slug rather than UEX's bare one), but as an exact-equality last resort it recovers
 *       entries whose display name the matcher could not resolve. Fleetview and HangarXPLOR entries
 *       carry a {@code null} slug and skip this stage entirely.
 * </ol>
 *
 * <p>All five stages are powered by a single {@code shipTypeRepository.findAll()} call up front, so
 * per-entry resolution is in-memory rather than a database round-trip — there are no N+1 queries
 * even for fleet exports with hundreds of entries.
 *
 * <p>Unmatched entries are collected in the response's {@code skippedShips} list (deduplicated
 * case-insensitively, original casing preserved) so the user can see exactly which ships still need
 * manual correction or a fresh UEX sync.
 *
 * <p>Duplicate handling: if a {@code ShipType} appears multiple times in the upload, the import
 * ensures that the user's hangar contains <em>at least</em> as many ships of that type as the
 * upload specifies. The number of ships to create is {@code max(0, jsonCount - hangarCount)}. Ships
 * already present in excess of the upload count are <strong>never deleted</strong>. The {@code
 * duplicateCount} field in {@link FleetviewImportResponseDto} reports the number of entries that
 * resolved to a {@code ShipType} where the hangar count already met or exceeded the upload count
 * (no new ships needed).
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class HangarImportService {

  /**
   * Neutral insurance default used for imported ships when no explicit insurance information is
   * carried by the upload (Fleetview always, Shiplist when {@code lti=false} or absent).
   */
  static final String DEFAULT_INSURANCE = "0";

  /**
   * Insurance string set on imported ships when the HangarXPLOR record carries {@code lti=true}.
   */
  static final String LTI_INSURANCE = "LTI";

  /**
   * Application-level cap on a hangar/fleet upload, enforced before the body is materialised into a
   * Jackson tree (security audit gap-fill). A real ship-list export is well under 1 MB; 8 MB leaves
   * generous headroom for very large fleets while keeping this member-reachable import off the 64
   * MB global multipart cap (sized for the admin-only P4K catalogue) — a flat multi-MB array would
   * otherwise expand into hundreds of MB of transient heap per request.
   */
  private static final long MAX_IMPORT_BYTES = 8L * 1024 * 1024;

  private final ShipRepository shipRepository;
  private final ShipTypeRepository shipTypeRepository;
  private final UserRepository userRepository;
  private final ObjectMapper objectMapper;
  private final OwnerScopeService ownerScopeService;

  /**
   * Parses an uploaded ship-export JSON file (CCU Game Fleetview, HangarXPLOR Shiplist, Fleetyards
   * or StarJump FleetViewer / "Hangar Link") and imports all resolvable ships into the given user's
   * hangar.
   *
   * <p>The matcher walks four progressively-looser name stages (exact case-insensitive, normalised,
   * fv-tokens-subset-of-uex, uex-tokens-subset-of-fv) and — for StarJump FleetViewer and Fleetyards
   * entries (the slug-carrying formats) — a final slug-fallback stage; each later stage runs only
   * if the earlier ones produced no hit. Stages 3 and 4 require the candidate to be
   * <em>unique</em>; ambiguity leaves the entry unresolved on purpose so the user must explicitly
   * disambiguate (e.g. choose Mk I vs Mk II) instead of having the import guess.
   *
   * <p>The import then ensures that after the operation the hangar contains at least as many ships
   * of each type as the upload specifies; ships already present are never removed. For each
   * distinct {@code ShipType} resolved from the upload the number of new ships created equals
   * {@code max(0, jsonCount - hangarCount)}. Entries that could not be resolved against any {@code
   * ShipType} are surfaced through the response's {@code skippedShips} list so the caller can act
   * on them.
   *
   * @param userId user ID from the JWT {@code sub} claim
   * @param file multipart file containing a CCU Game Fleetview, HangarXPLOR Shiplist, Fleetyards or
   *     StarJump FleetViewer JSON export
   * @return import result with statistics and the deduplicated list of unmatched ship names
   * @throws BadRequestException if the file is empty, not parseable as JSON, or in an unknown
   *     format
   * @throws NotFoundException if the user is not found
   */
  @Transactional
  public @NotNull FleetviewImportResponseDto importShips(
      @NotNull UUID userId, @NotNull MultipartFile file) {
    if (file.isEmpty()) {
      throw new BadRequestException("The uploaded file is empty.");
    }

    List<FleetImportEntry> entries = parseEntries(file);

    User user =
        userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));

    // Phase 0: one query to build the tolerant lookup index covering all four match stages.
    ShipTypeMatcher.ShipTypeIndex index = ShipTypeMatcher.buildIndex(shipTypeRepository.findAll());

    // Phase 1: resolve every entry and aggregate per-type counts.
    Set<String> seenSkipped = new HashSet<>();
    List<String> skippedShips = new ArrayList<>();
    Map<UUID, Integer> uploadCountByTypeId = new LinkedHashMap<>();
    Map<UUID, ShipType> shipTypeById = new LinkedHashMap<>();
    Map<UUID, FleetImportEntry> firstEntryByTypeId = new LinkedHashMap<>();

    for (FleetImportEntry entry : entries) {
      String trimmed = entry.name().trim();
      ShipType match = ShipTypeMatcher.resolve(index, trimmed, entry.slug());

      if (match == null) {
        log.debug("Hangar import: no ShipType match for '{}' (user {})", entry.name(), userId);
        if (seenSkipped.add(trimmed.toLowerCase(Locale.ROOT))) {
          skippedShips.add(entry.name());
        }
        continue;
      }

      UUID typeId = match.getId();
      uploadCountByTypeId.merge(typeId, 1, Integer::sum);
      shipTypeById.putIfAbsent(typeId, match);
      firstEntryByTypeId.putIfAbsent(typeId, entry);
    }

    // Phase 2: for each distinct ShipType, create only the missing ships.
    int importedCount = 0;
    int alreadySufficientCount = 0;

    for (Map.Entry<UUID, Integer> e : uploadCountByTypeId.entrySet()) {
      UUID typeId = e.getKey();
      ShipType shipType = shipTypeById.get(typeId);
      int jsonCount = e.getValue();
      long hangarCount = shipRepository.countByOwnerIdAndShipTypeId(userId, typeId);
      int toCreate = (int) Math.max(0L, jsonCount - hangarCount);

      if (toCreate > 0) {
        FleetImportEntry firstEntry = firstEntryByTypeId.get(typeId);
        String individualName = firstEntry != null ? firstEntry.individualName() : null;
        String insurance =
            (firstEntry != null && firstEntry.insurance() != null)
                ? firstEntry.insurance()
                : DEFAULT_INSURANCE;

        for (int i = 0; i < toCreate; i++) {
          Ship ship = new Ship();
          ship.setOwner(user);
          // Stamp owning org-unit through the shared nullable picker resolver — same contract as
          // HangarService.addShip's create path. The import flow has no picker UI; passing
          // {@code null} for the picker output triggers the resolver's "auto-stamp the single
          // membership" branch for a single-membership importer, yields an ownerless personal ship
          // ({@code owningOrgUnit == null}) for a membershipless importer (V132 made the column
          // nullable for exactly this), and surfaces a multi-membership importer as a clean 400
          // until a per-import picker is added (post-SK §5.5 stamping wave).
          ship.setOwningOrgUnit(
              ownerScopeService.resolveOrgUnitForPickerOutputNullable(user, null));
          ship.setShipType(shipType);
          ship.setInsurance(insurance);
          ship.setFitted(false);
          ship.setName(i == 0 ? individualName : null);
          shipRepository.save(ship);
        }

        log.info(
            "Hangar import: created {} ship(s) of type '{}' for user {} (jsonCount={},"
                + " hangarCount={})",
            toCreate,
            shipType.getName(),
            userId,
            jsonCount,
            hangarCount);
        importedCount += toCreate;
      } else {
        log.debug(
            "Hangar import: hangar already has {} ship(s) of type '{}', upload requests {} —"
                + " skipping (user {})",
            hangarCount,
            shipType.getName(),
            jsonCount,
            userId);
        alreadySufficientCount += jsonCount;
      }
    }

    log.info(
        "Hangar import for user {}: imported={}, alreadySufficient={}, skipped={}",
        userId,
        importedCount,
        alreadySufficientCount,
        skippedShips.size());

    return new FleetviewImportResponseDto(
        importedCount, skippedShips.size(), alreadySufficientCount, skippedShips, List.of());
  }

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
   * @param file multipart upload from the controller
   * @return parsed entries (possibly empty); never {@code null}
   * @throws BadRequestException if the JSON is malformed, the root is neither an array nor a
   *     recognised FleetViewer object, or no field on the first array element matches a known
   *     format
   */
  private @NotNull List<FleetImportEntry> parseEntries(@NotNull MultipartFile file) {
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
      return parseStarjumpEntries(root);
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
          .map(HangarImportService::mapShiplistEntry)
          .filter(Objects::nonNull)
          .toList();
    }
    if (probe.has("shipname") || probe.has("type")) {
      List<FleetviewEntryDto> raw =
          objectMapper.convertValue(root, new TypeReference<List<FleetviewEntryDto>>() {});
      return raw.stream()
          .map(HangarImportService::mapFleetviewEntry)
          .filter(Objects::nonNull)
          .toList();
    }
    if (probe.has("shipCode") || probe.has("manufacturerCode")) {
      List<FleetyardsEntryDto> raw =
          objectMapper.convertValue(root, new TypeReference<List<FleetyardsEntryDto>>() {});
      return raw.stream()
          .map(HangarImportService::mapFleetyardsEntry)
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
   * @param root the parsed JSON object root (already confirmed as FleetViewer)
   * @return parsed entries (possibly empty); never {@code null}
   */
  private @NotNull List<FleetImportEntry> parseStarjumpEntries(@NotNull JsonNode root) {
    StarjumpFleetviewerDto dto = objectMapper.convertValue(root, StarjumpFleetviewerDto.class);
    if (dto == null || dto.canvasItems() == null) {
      return List.of();
    }
    return dto.canvasItems().stream()
        .map(HangarImportService::mapStarjumpEntry)
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
   * normalisation as the matcher: if the normalised custom name is a substring of the normalised
   * model name, treat it as an echo and return {@code null}. Empirically (n=64 in the real
   * shiplist) this keeps the three actual custom names ({@code "KRT Olymp"}, {@code "KRT Falcon"},
   * {@code "KRT Franklin"}) and discards 12 model-name echoes like {@code "325a"} → {@code "325a
   * Fighter"} or {@code "Caterpillar"} → {@code "Caterpillar Pirate Edition"}.
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
   * / FleetViewer mapping. Used internally so the resolver does not have to branch on the source
   * format.
   *
   * @param name the ship-model name to resolve against {@code ShipType.name}
   * @param individualName custom display name for the ship, or {@code null}
   * @param insurance explicit insurance string (e.g. {@code "LTI"}), or {@code null} to let the
   *     service fall back to {@link #DEFAULT_INSURANCE}
   * @param slug source-provided ship slug used for the slug-fallback match stage (StarJump
   *     FleetViewer and Fleetyards), or {@code null} for formats that carry no slug
   */
  private record FleetImportEntry(
      @NotNull String name,
      @Nullable String individualName,
      @Nullable String insurance,
      @Nullable String slug) {}
}
