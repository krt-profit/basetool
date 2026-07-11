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
import de.greluc.krt.profit.basetool.backend.model.dto.FleetviewImportResponseDto;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
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

    List<FleetExportParser.FleetImportEntry> entries = FleetExportParser.parse(objectMapper, file);

    User user =
        userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));

    // Phase 0: one query to build the tolerant lookup index covering all four match stages.
    ShipTypeMatcher.ShipTypeIndex index = ShipTypeMatcher.buildIndex(shipTypeRepository.findAll());

    // Phase 1: resolve every entry and aggregate per-type counts.
    Set<String> seenSkipped = new HashSet<>();
    List<String> skippedShips = new ArrayList<>();
    Map<UUID, Integer> uploadCountByTypeId = new LinkedHashMap<>();
    Map<UUID, ShipType> shipTypeById = new LinkedHashMap<>();
    Map<UUID, FleetExportParser.FleetImportEntry> firstEntryByTypeId = new LinkedHashMap<>();

    for (FleetExportParser.FleetImportEntry entry : entries) {
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
        FleetExportParser.FleetImportEntry firstEntry = firstEntryByTypeId.get(typeId);
        String individualName = firstEntry != null ? firstEntry.individualName() : null;
        String insurance =
            (firstEntry != null && firstEntry.insurance() != null)
                ? firstEntry.insurance()
                : FleetExportParser.DEFAULT_INSURANCE;

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
}
