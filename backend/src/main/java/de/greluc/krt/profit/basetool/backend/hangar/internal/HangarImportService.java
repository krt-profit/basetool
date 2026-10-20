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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipTypeMatcher;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

/**
 * Imports a third-party ship export into a user's hangar.
 *
 * <p>Accepts CCU Game Fleetview, HangarXPLOR Shiplist, Fleetyards, StarJump FleetViewer and the
 * Hangar Extension JSON and CSV exports, auto-detected from the payload. Each entry is resolved
 * against the cached {@code ShipType} rows — the CSV's name without its manufacturer word first —
 * by progressively looser name matching (exact, normalised, then unique token-subset in both
 * directions) and, for the slug-carrying formats, a final slug match; ambiguous entries stay
 * unresolved.
 *
 * <p>Per ship type, each entry is matched to one ship already in the hangar — first by pledge id,
 * then to a ship without a pledge id (preferring the same insurance) — and only the entries left
 * over create ships. A matched ship takes the entry's production status and pledge data; existing
 * ships are never deleted (REQ-HANGAR-015). Unmatched names are reported in {@code skippedShips}.
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
  private final AuditRecorder auditRecorder;
  private final HangarUnitLiveSync unitLiveSync;

  /**
   * Parses an uploaded ship export, updates the hangar ships it matches and creates the ships it
   * lists beyond them. Ambiguous or unknown names are returned in {@code skippedShips} rather than
   * guessed.
   *
   * @param userId user ID from the JWT {@code sub} claim
   * @param file the uploaded export in one of the supported formats
   * @return import statistics and the deduplicated list of unmatched ship names
   * @throws BadRequestException if the file is empty, unreadable, or in an unknown format
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
    Map<UUID, List<FleetExportParser.FleetImportEntry>> entriesByTypeId = new LinkedHashMap<>();
    Map<UUID, ShipType> shipTypeById = new HashMap<>();

    for (FleetExportParser.FleetImportEntry entry : entries) {
      String trimmed = entry.name().trim();
      ShipType match =
          entry.unprefixedName() == null
              ? null
              : ShipTypeMatcher.resolve(index, entry.unprefixedName(), null);
      if (match == null) {
        match = ShipTypeMatcher.resolve(index, trimmed, entry.slug());
      }

      if (match == null) {
        log.debug("Hangar import: no ShipType match for '{}' (user {})", entry.name(), userId);
        if (seenSkipped.add(trimmed.toLowerCase(Locale.ROOT))) {
          skippedShips.add(entry.name());
        }
        continue;
      }

      entriesByTypeId.computeIfAbsent(match.getId(), _ -> new ArrayList<>()).add(entry);
      shipTypeById.putIfAbsent(match.getId(), match);
    }

    Map<UUID, List<Ship>> hangarByTypeId = new HashMap<>();
    if (!entriesByTypeId.isEmpty()) {
      for (Ship ship : shipRepository.lockAllOwnedByOrderById(userId)) {
        hangarByTypeId
            .computeIfAbsent(ship.getShipType().getId(), _ -> new ArrayList<>())
            .add(ship);
      }
    }

    ImportTally tally = new ImportTally();
    for (Map.Entry<UUID, List<FleetExportParser.FleetImportEntry>> e : entriesByTypeId.entrySet()) {
      ShipType shipType = Objects.requireNonNull(shipTypeById.get(e.getKey()));
      List<Ship> pool = new ArrayList<>(hangarByTypeId.getOrDefault(e.getKey(), List.of()));
      List<FleetExportParser.FleetImportEntry> unplaced =
          matchByPledgeId(e.getValue(), pool, tally);
      List<FleetExportParser.FleetImportEntry> toCreate = new ArrayList<>();
      for (FleetExportParser.FleetImportEntry entry : unplaced) {
        Predicate<Ship> eligible =
            entry.pledge() != null ? s -> s.getPledgeId() == null : _ -> true;
        Ship claimed =
            takeFirst(pool, eligible.and(s -> Objects.equals(s.getInsurance(), entry.insurance())));
        if (claimed == null) {
          claimed = takeFirst(pool, eligible);
        }
        if (claimed == null) {
          toCreate.add(entry);
        } else {
          tally.matched(claimed, applyImported(claimed, entry));
        }
      }
      for (FleetExportParser.FleetImportEntry entry : toCreate) {
        createShip(user, shipType, entry, tally);
      }
      if (!toCreate.isEmpty()) {
        log.info(
            "Hangar import: created {} ship(s) of type '{}' for user {}",
            toCreate.size(),
            shipType.getName(),
            userId);
      }
    }

    log.info(
        "Hangar import for user {}: imported={}, updated={}, alreadyPresent={}, skipped={}",
        userId,
        tally.created,
        tally.updated,
        tally.alreadyPresent,
        skippedShips.size());

    if (tally.created > 0 || tally.updated > 0) {
      auditRecorder.record(
          AuditEventType.HANGAR_IMPORTED,
          null,
          null,
          userId,
          AuditDetails.of("created", tally.created)
              .with("updated", tally.updated)
              .with("alreadyPresent", tally.alreadyPresent)
              .with("unmatched", skippedShips.size()));
      unitLiveSync.shipsChanged(new ArrayList<>(tally.affectedUnits));
    }

    return new FleetviewImportResponseDto(
        tally.created,
        skippedShips.size(),
        tally.alreadyPresent,
        skippedShips,
        List.of(),
        tally.updated);
  }

  /**
   * Matches every entry carrying a pledge id to an unmatched ship of the same pledge id.
   *
   * @param entries the entries of one ship type
   * @param pool the not yet matched hangar ships of that type; matched ships are removed
   * @param tally the running counts
   * @return the entries left unmatched, in upload order
   */
  private @NotNull List<FleetExportParser.FleetImportEntry> matchByPledgeId(
      @NotNull List<FleetExportParser.FleetImportEntry> entries,
      @NotNull List<Ship> pool,
      @NotNull ImportTally tally) {
    List<FleetExportParser.FleetImportEntry> unplaced = new ArrayList<>();
    for (FleetExportParser.FleetImportEntry entry : entries) {
      FleetExportParser.PledgeInfo pledge = entry.pledge();
      Ship matched =
          pledge == null ? null : takeFirst(pool, s -> pledge.pledgeId().equals(s.getPledgeId()));
      if (matched == null) {
        unplaced.add(entry);
      } else {
        tally.matched(matched, applyImported(matched, entry));
      }
    }
    return unplaced;
  }

  /**
   * Creates one ship for an entry with its insurance, name, production status and pledge data.
   *
   * @param user the owner
   * @param shipType the resolved ship type
   * @param entry the upload entry
   * @param tally the running counts, which also cache the owning org unit
   */
  private void createShip(
      @NotNull User user,
      @NotNull ShipType shipType,
      @NotNull FleetExportParser.FleetImportEntry entry,
      @NotNull ImportTally tally) {
    if (!tally.owningOrgUnitResolved) {
      tally.owningOrgUnit = ownerScopeService.resolveOrgUnitForImport(user);
      tally.owningOrgUnitResolved = true;
    }
    Ship ship = new Ship();
    ship.setOwner(user);
    ship.setOwningOrgUnit(tally.owningOrgUnit);
    ship.setShipType(shipType);
    ship.setInsurance(
        entry.insurance() != null ? entry.insurance() : FleetExportParser.DEFAULT_INSURANCE);
    ship.setFitted(false);
    ship.setName(entry.individualName());
    applyImported(ship, entry);
    shipRepository.save(ship);
    tally.created++;
    tally.affectedUnits.add(tally.owningOrgUnit);
  }

  /**
   * Writes an entry's production status and pledge data onto a ship; values the entry does not
   * carry stay as they are.
   *
   * @param ship the ship to update
   * @param entry the upload entry
   * @return {@code true} when a stored value changed
   */
  static boolean applyImported(
      @NotNull Ship ship, @NotNull FleetExportParser.FleetImportEntry entry) {
    boolean changed = false;
    if (entry.productionStatus() != null
        && entry.productionStatus() != ship.getProductionStatus()) {
      ship.setProductionStatus(entry.productionStatus());
      changed = true;
    }
    FleetExportParser.PledgeInfo pledge = entry.pledge();
    if (pledge == null) {
      return changed;
    }
    if (!pledge.pledgeId().equals(ship.getPledgeId())) {
      ship.setPledgeId(pledge.pledgeId());
      changed = true;
    }
    if (!Objects.equals(pledge.pledgeDate(), ship.getPledgeDate())) {
      ship.setPledgeDate(pledge.pledgeDate());
      changed = true;
    }
    if (!sameAmount(pledge.pledgeCost(), ship.getPledgeCost())) {
      ship.setPledgeCost(pledge.pledgeCost());
      changed = true;
    }
    if (!Objects.equals(pledge.pledgeCostCurrency(), ship.getPledgeCostCurrency())) {
      ship.setPledgeCostCurrency(pledge.pledgeCostCurrency());
      changed = true;
    }
    if (!Objects.equals(pledge.meltable(), ship.getMeltable())) {
      ship.setMeltable(pledge.meltable());
      changed = true;
    }
    if (!Objects.equals(pledge.giftable(), ship.getGiftable())) {
      ship.setGiftable(pledge.giftable());
      changed = true;
    }
    return changed;
  }

  /**
   * Compares two amounts by value, so {@code 5.0} equals {@code 5.00}.
   *
   * @param a the first amount, or {@code null}
   * @param b the second amount, or {@code null}
   * @return {@code true} when both are {@code null} or numerically equal
   */
  private static boolean sameAmount(@Nullable BigDecimal a, @Nullable BigDecimal b) {
    if (a == null || b == null) {
      return a == null && b == null;
    }
    return a.compareTo(b) == 0;
  }

  /**
   * Removes and returns the first ship of the pool that the predicate accepts.
   *
   * @param pool the candidate ships, in ascending id order
   * @param accept the condition
   * @return the removed ship, or {@code null} when none qualifies
   */
  private static @Nullable Ship takeFirst(
      @NotNull List<Ship> pool, @NotNull Predicate<Ship> accept) {
    for (Iterator<Ship> it = pool.iterator(); it.hasNext(); ) {
      Ship ship = it.next();
      if (accept.test(ship)) {
        it.remove();
        return ship;
      }
    }
    return null;
  }

  /** Running counts of one import and the org units whose overview it changed. */
  private static final class ImportTally {
    private int created;
    private int updated;
    private int alreadyPresent;
    private final Set<@Nullable OrgUnit> affectedUnits = new HashSet<>();
    private @Nullable OrgUnit owningOrgUnit;
    private boolean owningOrgUnitResolved;

    /**
     * Counts a matched hangar ship.
     *
     * @param ship the matched ship
     * @param changed whether the import changed one of its values
     */
    void matched(@NotNull Ship ship, boolean changed) {
      alreadyPresent++;
      if (changed) {
        updated++;
        affectedUnits.add(ship.getOwningOrgUnit());
      }
    }
  }
}
