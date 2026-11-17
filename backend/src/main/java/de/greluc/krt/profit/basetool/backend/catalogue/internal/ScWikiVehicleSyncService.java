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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiResponseDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiVehicleDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Applies identity-checked Wiki vehicle facts in isolated transactions. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScWikiVehicleSyncService {

  private static final int PRESENCE_BATCH_SIZE = 500;
  private static final double MICRO_SCU_PER_SCU = 1_000_000.0;
  private final ScWikiClient scWikiClient;
  private final ScWikiProperties properties;
  private final ShipTypeRepository shipTypeRepository;
  private final SyncReportService syncReportService;
  private final SyncChunkWriter chunkWriter;

  /**
   * Imports the configured vehicle census without holding a transaction during HTTP requests.
   *
   * @return successfully applied vehicle rows
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public int syncVehicles() {
    if (!Boolean.TRUE.equals(properties.vehicleSyncEnabled())) {
      return 0;
    }
    try (var _ = scWikiClient.openSyncRun()) {
      return syncVehicleCensus();
    }
  }

  private int syncVehicleCensus() {
    var result =
        scWikiClient.fetchAllPagesResult(
            properties.vehiclesEndpoint(),
            new ParameterizedTypeReference<ScWikiResponseDto<ScWikiVehicleDto>>() {},
            "vehicles",
            null,
            null,
            properties.vehiclesPageSize());

    List<ScWikiVehicleDto> rows = result.data();
    Set<UUID> seen = new HashSet<>();
    List<ScWikiVehicleDto> valid =
        rows.stream()
            .filter(dto -> dto != null && dto.uuid() != null && StringUtils.hasText(dto.name()))
            .toList();
    valid.forEach(dto -> seen.add(dto.uuid()));
    if (valid.size() != rows.size() || seen.size() != rows.size()) {
      syncReportService.recordIncomplete();
    }
    Map<String, Long> nameCounts =
        valid.stream()
            .flatMap(dto -> nameKeys(dto).stream().map(ScWikiVehicleSyncService::fold))
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    UUID runId = syncReportService.beginRun();
    Instant now = Instant.now();
    var run = new VehicleRun(nameCounts, Set.copyOf(seen), runId, now);
    var outcome =
        chunkWriter.write(
            valid,
            SyncChunkWriter.DEFAULT_CHUNK_SIZE,
            chunk ->
                chunk.stream()
                    .map(dto -> upsert(dto, run))
                    .filter(disposition -> disposition != null)
                    .toList(),
            "Wiki vehicle",
            dto -> dto.uuid().toString());
    boolean complete =
        result.complete()
            && valid.size() == rows.size()
            && seen.size() == rows.size()
            && outcome.results().size() == valid.size()
            && outcome.failedRows() == 0;
    int restored = 0;
    if (complete && !seen.isEmpty()) {
      List<UUID> known =
          chunkWriter.inNewTransaction(shipTypeRepository::findWikiLinkedVehicleUuids);
      if (!seen.containsAll(known)) {
        var existence =
            scWikiClient.fetchExistenceCensus(
                properties.vehiclesEndpoint(), "vehicle existence", properties.vehiclesPageSize());
        complete = existence.complete() && existence.uuids().containsAll(seen);
        if (complete) {
          List<UUID> present = new ArrayList<>();
          for (UUID uuid : known) {
            if (!seen.contains(uuid) && existence.uuids().contains(uuid)) {
              present.add(uuid);
            }
          }
          restored = restorePresent(present, runId, now);
          seen.addAll(present);
        } else {
          syncReportService.recordIncomplete();
        }
      }
    }
    if (complete && !seen.isEmpty()) {
      int marked =
          chunkWriter.inNewTransaction(() -> shipTypeRepository.markScwikiDeletedExcept(seen, now));
      syncReportService.recordApplied(marked);
      syncReportService.recordSweep(true);
    } else {
      syncReportService.recordSweep(false);
    }
    syncReportService.pruneRuns(SyncSourceSystem.SCWIKI);
    long written =
        outcome.results().stream()
            .filter(disposition -> disposition == Disposition.WRITTEN)
            .count();
    return Math.toIntExact(written) + restored;
  }

  private int restorePresent(List<UUID> present, UUID runId, Instant now) {
    int restored = 0;
    for (int from = 0; from < present.size(); from += PRESENCE_BATCH_SIZE) {
      List<UUID> batch =
          present.subList(from, Math.min(present.size(), from + PRESENCE_BATCH_SIZE));
      int changed =
          chunkWriter.inNewTransaction(
              () -> shipTypeRepository.restoreWikiPresentVehicles(batch, now));
      restored += changed;
      syncReportService.recordApplied(changed);
    }
    if (restored > 0) {
      syncReportService.logScwikiEvent(
          runId,
          SyncEventType.SOURCE_RECORDS_RESTORED,
          "ship_type",
          null,
          null,
          "restored=" + restored + ";scope=all_assets");
    }
    return restored;
  }

  /**
   * Links or seeds the ship type of one Wiki vehicle, resolved by game UUID, P4K GUID, aliased
   * class name, then name and in-game name (REQ-DATA-026, REQ-DATA-041).
   *
   * @param dto the Wiki vehicle
   * @param run the census-wide facts of this run
   * @return {@link Disposition#WRITTEN} for a saved row, {@link Disposition#COVERED_BY_SIBLING} for
   *     a variant whose shared name a ship type of this census holds, {@link
   *     Disposition#NAME_HELD_BY_OTHER_VEHICLE} for a name held by a departed vehicle of another
   *     class, {@code null} for a conflict
   */
  @Nullable
  private Disposition upsert(ScWikiVehicleDto dto, VehicleRun run) {
    MatchKey matchedBy = MatchKey.EXTERNAL_UUID;
    Optional<ShipType> match = shipTypeRepository.findByExternalUuid(dto.uuid());
    if (match.isEmpty()) {
      matchedBy = MatchKey.P4K_UUID;
      match = shipTypeRepository.findByP4kUuid(dto.uuid());
    }
    if (match.isEmpty() && StringUtils.hasText(dto.className())) {
      matchedBy = MatchKey.UEX_ALIAS;
      match = shipTypeRepository.findByUexAliasOfClassName(dto.className());
    }
    if (match.isEmpty()) {
      matchedBy = MatchKey.NAME;
      Optional<NameHit> named = findByName(dto);
      if (named.isPresent() && run.sharesName(named.get().key())) {
        ShipType holder = named.get().ship();
        if (run.covers(holder)) {
          return reportSiblingVariant(dto, run.runId());
        }
        return run.isOtherVehicle(holder, dto)
            ? reportNameHeldByOtherVehicle(dto, run.runId())
            : reportIdentityConflict(dto, run.runId(), "Name is shared by several Wiki vehicles.");
      }
      match = named.map(NameHit::ship);
    }
    ShipType ship = match.orElseGet(ShipType::new);
    UUID stored = ship.getExternalUuid();
    if (stored != null && !stored.equals(dto.uuid())) {
      if (matchedBy == MatchKey.P4K_UUID && !run.census().contains(stored)) {
        reportRelinked(dto, stored, run.runId());
      } else if (matchedBy == MatchKey.NAME && run.isOtherVehicle(ship, dto)) {
        return reportNameHeldByOtherVehicle(dto, run.runId());
      } else {
        return reportIdentityConflict(dto, run.runId(), "Existing vehicle has another game UUID.");
      }
    }
    if (match.isEmpty()) {
      ship.setName(dto.name());
      ship.setSourceSystems(GameItemSourceSystem.WIKI_ONLY);
    }
    ship.setExternalUuid(dto.uuid());
    P4kLedColumns led = new P4kLedColumns(ship);
    applyWikiFields(ship, dto, run.now(), led);
    if (!led.disagreements().isEmpty()) {
      syncReportService.logScwikiEvent(
          run.runId(),
          SyncEventType.P4K_DISAGREEMENT,
          "ship_type",
          dto.uuid(),
          dto.name(),
          String.join(" | ", led.disagreements()));
    }
    if (ship.getSourceSystems() == GameItemSourceSystem.UEX_ONLY) {
      ship.setSourceSystems(GameItemSourceSystem.BOTH);
    }
    shipTypeRepository.save(ship);
    return Disposition.WRITTEN;
  }

  /**
   * Finds the ship type carrying the Wiki vehicle's name, else its in-game name, which the P4K
   * import leads into {@code ship_type.name} (REQ-DATA-041).
   *
   * @param dto the Wiki vehicle
   * @return the ship type and the name that found it, or empty when neither name is stored
   */
  private Optional<NameHit> findByName(ScWikiVehicleDto dto) {
    for (String key : nameKeys(dto)) {
      Optional<ShipType> ship = shipTypeRepository.findByNameIgnoreCase(key);
      if (ship.isPresent()) {
        return Optional.of(new NameHit(ship.get(), key));
      }
    }
    return Optional.empty();
  }

  /**
   * Lists the Wiki vehicle's name and, when it differs, its in-game name, in that lookup order.
   *
   * @param dto the Wiki vehicle
   * @return the nonblank names
   */
  private static List<String> nameKeys(ScWikiVehicleDto dto) {
    String gameName = dto.gameName();
    return StringUtils.hasText(gameName) && !fold(gameName).equals(fold(dto.name()))
        ? List.of(dto.name(), gameName)
        : List.of(dto.name());
  }

  /**
   * Folds a name to its census key, ignoring case as the name lookup does.
   *
   * @param name the name
   * @return the lower-cased name
   */
  private static String fold(String name) {
    return name.toLowerCase(Locale.ROOT);
  }

  /**
   * A ship type found by one of a Wiki vehicle's names.
   *
   * @param ship the stored ship type
   * @param key the name that found it
   */
  private record NameHit(ShipType ship, String key) {}

  /**
   * Reports a Wiki variant left without a row of its own because a ship type linked to another
   * vehicle of this census holds its shared name; the census still covers it (REQ-DATA-014).
   *
   * @param dto the Wiki variant left unlinked
   * @param runId the sync run the event belongs to
   * @return always {@link Disposition#COVERED_BY_SIBLING}
   */
  private Disposition reportSiblingVariant(ScWikiVehicleDto dto, UUID runId) {
    syncReportService.logScwikiEvent(
        runId,
        SyncEventType.SOURCE_IDENTITY_CONFLICT,
        "ship_type",
        dto.uuid(),
        dto.name(),
        "Name is held by a sibling Wiki vehicle of this census.");
    return Disposition.COVERED_BY_SIBLING;
  }

  /**
   * Reports a Wiki vehicle left without a row of its own because a ship type of another class that
   * this census no longer carries holds its name; the census still covers it (REQ-DATA-026).
   *
   * @param dto the Wiki vehicle left unlinked
   * @param runId the sync run the event belongs to
   * @return always {@link Disposition#NAME_HELD_BY_OTHER_VEHICLE}
   */
  private Disposition reportNameHeldByOtherVehicle(ScWikiVehicleDto dto, UUID runId) {
    syncReportService.logScwikiEvent(
        runId,
        SyncEventType.SOURCE_IDENTITY_CONFLICT,
        "ship_type",
        dto.uuid(),
        dto.name(),
        "Name is held by a ship type of another class that this census no longer carries.");
    return Disposition.NAME_HELD_BY_OTHER_VEHICLE;
  }

  /**
   * Reports a ship type that moves to the Wiki vehicle carrying its P4K GUID because the census no
   * longer carries its previous game UUID (REQ-DATA-026).
   *
   * @param dto the Wiki vehicle the ship type is linked to
   * @param previous the game UUID the ship type held
   * @param runId the sync run the event belongs to
   */
  private void reportRelinked(ScWikiVehicleDto dto, UUID previous, UUID runId) {
    syncReportService.logScwikiEvent(
        runId,
        SyncEventType.VEHICLE_RELINKED,
        "ship_type",
        dto.uuid(),
        dto.name(),
        "previousUuid=" + previous);
  }

  /**
   * Reports a Wiki vehicle that stays unlinked because its identity collides with a stored ship
   * type, and marks the run incomplete so no sweep relies on it.
   *
   * @param dto the Wiki vehicle left unlinked
   * @param runId the sync run the event belongs to
   * @param reason the fixed, non-user text naming the collision
   * @return always {@code null}, the upsert's "nothing written" result
   */
  @Nullable
  private Disposition reportIdentityConflict(ScWikiVehicleDto dto, UUID runId, String reason) {
    syncReportService.logScwikiEvent(
        runId, SyncEventType.SOURCE_IDENTITY_CONFLICT, "ship_type", dto.uuid(), dto.name(), reason);
    syncReportService.recordIncomplete();
    return null;
  }

  /** How a Wiki vehicle of a complete census is accounted for. */
  private enum Disposition {
    /** The vehicle's ship type row was linked or seeded and saved. */
    WRITTEN,
    /** The vehicle has no row of its own; a sibling of this census holds its shared name. */
    COVERED_BY_SIBLING,
    /**
     * The vehicle has no row of its own; a ship type of another class, absent from this census,
     * holds its name.
     */
    NAME_HELD_BY_OTHER_VEHICLE
  }

  /** The identity key that found a vehicle's stored ship type. */
  private enum MatchKey {
    /** The ship type holds the vehicle's UUID as its {@code external_uuid}. */
    EXTERNAL_UUID,
    /** The ship type holds the vehicle's UUID as the P4K GUID the catalogue import confirmed. */
    P4K_UUID,
    /** The ship type holds the UEX vehicle id aliased to the vehicle's class name. */
    UEX_ALIAS,
    /** The ship type carries the vehicle's name, ignoring case. */
    NAME
  }

  /**
   * The census-wide facts one vehicle upsert reads.
   *
   * @param nameCounts how many valid Wiki vehicles carry each lower-cased name as their name or
   *     in-game name
   * @param census the UUIDs of every valid Wiki vehicle of this run
   * @param runId the sync run the events belong to
   * @param now the sync time
   */
  private record VehicleRun(
      Map<String, Long> nameCounts, Set<UUID> census, UUID runId, Instant now) {

    /**
     * Tells whether more than one Wiki vehicle of this census carries a name as its name or in-game
     * name.
     *
     * @param name the name that found a stored ship type
     * @return {@code true} when the name is shared, ignoring case
     */
    boolean sharesName(String name) {
      return nameCounts.getOrDefault(fold(name), 0L) > 1;
    }

    /**
     * Tells whether a vehicle of this census links the ship type, through its game UUID or its P4K
     * GUID.
     *
     * @param ship the stored ship type
     * @return {@code true} when either identity is in the census
     */
    boolean covers(ShipType ship) {
      return inCensus(ship.getExternalUuid()) || inCensus(ship.getP4kUuid());
    }

    private boolean inCensus(@Nullable UUID uuid) {
      return uuid != null && census.contains(uuid);
    }

    /**
     * Tells whether the ship type holding the vehicle's name is a different vehicle that this
     * census no longer carries: its game UUID is absent and both class names differ.
     *
     * @param holder the stored ship type carrying the name
     * @param dto the Wiki vehicle
     * @return {@code true} when the holder is provably another, departed vehicle
     */
    boolean isOtherVehicle(ShipType holder, ScWikiVehicleDto dto) {
      return holder.getExternalUuid() != null
          && !covers(holder)
          && StringUtils.hasText(holder.getClassName())
          && StringUtils.hasText(dto.className())
          && !holder.getClassName().strip().equalsIgnoreCase(dto.className().strip());
    }
  }

  /**
   * Writes the Wiki's vehicle facts; the class name is written only while empty, and on a P4K-known
   * row a differing class name or hull health is reported instead (REQ-P4K-031).
   *
   * @param ship the row written
   * @param dto the Wiki vehicle
   * @param now the sync time
   * @param led the row's P4K-led column writes
   */
  private void applyWikiFields(
      ShipType ship, ScWikiVehicleDto dto, Instant now, P4kLedColumns led) {
    ship.setScwikiSlug(dto.slug());
    ship.setGameName(dto.gameName());
    ship.setScwikiGameVersionSeen(dto.version());
    if (!StringUtils.hasText(ship.getClassName())) {
      ship.setClassName(dto.className());
    } else if (ship.getP4kUuid() != null && StringUtils.hasText(dto.className())) {
      led.write("class_name", ship.getClassName(), dto.className(), ship::setClassName);
    }
    if (dto.vehicleInventory() != null) {
      ship.setVehicleInventoryScu(nonNegative(dto.vehicleInventory()) / MICRO_SCU_PER_SCU);
    }
    if (dto.oreCapacity() != null) {
      ship.setOreCapacity(nonNegative(dto.oreCapacity()));
    }
    if (dto.health() != null) {
      led.write("health", ship.getHealth(), nonNegative(dto.health()), ship::setHealth);
    }
    if (dto.shieldHp() != null) {
      ship.setShieldHp(nonNegative(dto.shieldHp()));
    }
    if (dto.maxMedicalTier() != null) {
      String tier = dto.maxMedicalTier();
      if (!tier.matches("T[0-3]")) {
        throw new IllegalArgumentException("Unknown medical service tier");
      }
      ship.setMaxMedicalTier(Integer.parseInt(tier.substring(1)));
    }
    if (dto.description() != null) {
      String english = dto.description().get("en_EN");
      if (!StringUtils.hasText(ship.getDescriptionEn()) && StringUtils.hasText(english)) {
        ship.setDescriptionEn(english);
      }
      ship.setDescriptionDe(dto.description().get("de_DE"));
    }
    ship.setScwikiSyncedAt(now);
    ship.setScwikiDeletedAt(null);
  }

  private static double nonNegative(double value) {
    if (!Double.isFinite(value) || value < 0) {
      throw new IllegalArgumentException("Vehicle quantity must be finite and nonnegative");
    }
    return value;
  }
}
