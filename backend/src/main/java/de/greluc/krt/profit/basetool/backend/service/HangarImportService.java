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
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.FleetviewImportResponseDto;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.ArrayList;
import java.util.HashMap;
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
 * Imports a third-party ship-export JSON into a user's hangar.
 *
 * <p>Accepts CCU Game Fleetview, HangarXPLOR Shiplist, Fleetyards and StarJump FleetViewer exports,
 * auto-detected from the payload shape. Each entry is resolved against the cached {@code ShipType}
 * rows by progressively looser name matching (exact, normalised, then unique token-subset in both
 * directions) and, for the slug-carrying formats, a final slug match; ambiguous entries stay
 * unresolved. Only {@link #DEFAULT_INSURANCE} or {@code "LTI"} is set as insurance.
 *
 * <p>The hangar ends up with at least as many ships per type as the upload lists; existing ships
 * are never deleted. Unmatched names are reported in {@code skippedShips}.
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
   * Parses an uploaded ship-export JSON file and adds every resolvable ship missing from the user's
   * hangar, creating {@code max(0, uploadCount - hangarCount)} ships per type. Ambiguous or unknown
   * names are returned in {@code skippedShips} rather than guessed.
   *
   * @param userId user ID from the JWT {@code sub} claim
   * @param file the uploaded JSON export in one of the supported formats
   * @return import statistics and the deduplicated list of unmatched ship names
   * @throws BadRequestException if the file is empty, not valid JSON, or in an unknown format
   * @throws NotFoundException if the user is not found
   */
  @Transactional
  public @NotNull FleetviewImportResponseDto importShips(
      @NotNull UUID userId, @NotNull MultipartFile file) {
    if (file.isEmpty()) {
      throw new BadRequestException("The uploaded file is empty.");
    }

    List<FleetExportParser.FleetImportEntry> entries = FleetExportParser.parse(objectMapper, file);

    User user = Entities.require(userRepository.findPlainById(userId), "User not found");

    ShipTypeMatcher.ShipTypeIndex index = ShipTypeMatcher.buildIndex(shipTypeRepository.findAll());

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

    int importedCount = 0;
    int alreadySufficientCount = 0;
    Map<UUID, Long> hangarCountByTypeId = new HashMap<>();
    if (!uploadCountByTypeId.isEmpty()) {
      for (ShipRepository.ShipTypeCount row : shipRepository.countShipsPerTypeByOwnerId(userId)) {
        hangarCountByTypeId.put(row.getShipTypeId(), row.getShipCount());
      }
    }
    OrgUnit owningOrgUnit = null;
    boolean owningOrgUnitResolved = false;

    for (Map.Entry<UUID, Integer> e : uploadCountByTypeId.entrySet()) {
      UUID typeId = e.getKey();
      ShipType shipType = shipTypeById.get(typeId);
      int jsonCount = e.getValue();
      long hangarCount = hangarCountByTypeId.getOrDefault(typeId, 0L);
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
          if (!owningOrgUnitResolved) {
            owningOrgUnit = ownerScopeService.resolveOrgUnitForPickerOutputNullable(user, null);
            owningOrgUnitResolved = true;
          }
          ship.setOwningOrgUnit(owningOrgUnit);
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
