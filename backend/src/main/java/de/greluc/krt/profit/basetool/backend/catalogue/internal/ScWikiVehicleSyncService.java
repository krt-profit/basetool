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
            .map(dto -> dto.name().toLowerCase(Locale.ROOT))
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    UUID runId = syncReportService.beginRun();
    Instant now = Instant.now();
    var outcome =
        chunkWriter.write(
            valid,
            SyncChunkWriter.DEFAULT_CHUNK_SIZE,
            chunk ->
                chunk.stream()
                    .map(dto -> upsert(dto, nameCounts, runId, now))
                    .filter(id -> id != null)
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
    return outcome.results().size() + restored;
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

  @Nullable
  private UUID upsert(ScWikiVehicleDto dto, Map<String, Long> nameCounts, UUID runId, Instant now) {
    Optional<ShipType> match = shipTypeRepository.findByExternalUuid(dto.uuid());
    if (match.isEmpty() && StringUtils.hasText(dto.className())) {
      match = shipTypeRepository.findByUexAliasOfClassName(dto.className());
    }
    if (match.isEmpty()) {
      Optional<ShipType> named = shipTypeRepository.findByNameIgnoreCase(dto.name());
      if (named.isPresent()
          && nameCounts.getOrDefault(dto.name().toLowerCase(Locale.ROOT), 0L) > 1) {
        return reportIdentityConflict(dto, runId, "Name is shared by several Wiki vehicles.");
      }
      match = named;
    }
    ShipType ship = match.orElseGet(ShipType::new);
    if (ship.getExternalUuid() != null && !ship.getExternalUuid().equals(dto.uuid())) {
      return reportIdentityConflict(dto, runId, "Existing vehicle has another game UUID.");
    }
    if (match.isEmpty()) {
      ship.setName(dto.name());
      ship.setSourceSystems(GameItemSourceSystem.WIKI_ONLY);
    }
    ship.setExternalUuid(dto.uuid());
    P4kLedColumns led = new P4kLedColumns(ship);
    applyWikiFields(ship, dto, now, led);
    if (!led.disagreements().isEmpty()) {
      syncReportService.logScwikiEvent(
          runId,
          SyncEventType.P4K_DISAGREEMENT,
          "ship_type",
          dto.uuid(),
          dto.name(),
          String.join(" | ", led.disagreements()));
    }
    if (ship.getSourceSystems() == GameItemSourceSystem.UEX_ONLY) {
      ship.setSourceSystems(GameItemSourceSystem.BOTH);
    }
    ShipType saved = shipTypeRepository.save(ship);
    return saved.getExternalUuid();
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
  private UUID reportIdentityConflict(ScWikiVehicleDto dto, UUID runId, String reason) {
    syncReportService.logScwikiEvent(
        runId, SyncEventType.SOURCE_IDENTITY_CONFLICT, "ship_type", dto.uuid(), dto.name(), reason);
    syncReportService.recordIncomplete();
    return null;
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
