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
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexVehicleDto;
import de.greluc.krt.profit.basetool.logging.LogSafe;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Syncs UEX vehicles into {@code ship_type}, writing the capability flags, dimensions, fuel and
 * URLs.
 *
 * <p>Resolution chain:
 *
 * <ol>
 *   <li>{@code findByUexVehicleId(dto.id)};
 *   <li>{@code findByExternalUuid(dto.uuid)}, only for an unambiguous UUID and an unclaimed row;
 *   <li>the class name the {@code ship_type_uex_alias} table ties {@code dto.id} to, when exactly
 *       one ship type carries it (REQ-DATA-026);
 *   <li>{@code findByNameIgnoreCase(dto.name)}, which backfills {@code external_uuid} and {@code
 *       uex_vehicle_id} on a hit;
 *   <li>a new row stamped {@link GameItemSourceSystem#UEX_ONLY}.
 * </ol>
 *
 * <p>On a row with a P4K GUID the manufacturer, cargo, maximum crew and mass are written only while
 * empty; a differing UEX value is reported as {@link SyncEventType#P4K_DISAGREEMENT} (REQ-P4K-031).
 * Descriptions are not written here, as UEX serves none. An empty response writes nothing; orphans
 * are marked via {@link ShipTypeRepository#markUexDeletedExcept(java.util.Collection, Instant)}
 * only for a complete pre-write source-id census with no rejected writes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UexVehicleService {

  /**
   * Maximum length of the untrusted upstream vehicle name in log lines, which passes through {@link
   * LogSafe}.
   */
  private static final int MAX_NAME_LOG_LENGTH = 64;

  private final UexClient uexClient;
  private final ShipTypeRepository shipTypeRepository;
  private final ManufacturerRepository manufacturerRepository;
  private final ManufacturerUexCompanyRepository manufacturerAliasRepository;
  private final SyncReportService syncReportService;

  /** Writes the rows in short isolated transactions after the fetch. */
  private final SyncChunkWriter chunkWriter;

  /** Pulls the UEX vehicle catalogue and upserts each row. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void syncVehicles() {
    log.info("Starting synchronization of UEX vehicles (ships)...");
    UexClient.FetchResult<UexVehicleDto> fetched = uexClient.getVehicles();
    List<UexVehicleDto> vehicles = fetched.data();
    if (vehicles.isEmpty()) {
      log.warn("No vehicles received from UEX API. Aborting synchronization.");
      return;
    }

    Instant now = Instant.now();
    UUID runId = syncReportService.beginRun();
    UexIdentityCensus census =
        UexIdentityCensus.of(
            vehicles,
            UexVehicleDto::id,
            UexVehicleDto::uuid,
            UexVehicleDto::name,
            fetched.complete());
    Set<Integer> seenUexVehicleIds = census.ids();
    SyncChunkWriter.Outcome<VehicleOutcome> outcome =
        chunkWriter.write(
            vehicles,
            SyncChunkWriter.DEFAULT_CHUNK_SIZE,
            chunk ->
                chunk.stream()
                    .map(dto -> upsertVehicle(dto, now, census, runId))
                    .filter(result -> result != VehicleOutcome.SKIPPED)
                    .toList(),
            "vehicle",
            dto ->
                "(id="
                    + dto.id()
                    + ", name='"
                    + LogSafe.text(dto.name(), MAX_NAME_LOG_LENGTH)
                    + "')");
    final long added = outcome.results().stream().filter(VehicleOutcome.CREATED::equals).count();
    final long updated = outcome.results().stream().filter(VehicleOutcome.UPDATED::equals).count();
    long declined = (long) vehicles.size() - outcome.results().size() - outcome.failedRows();
    long skipped = outcome.failedRows() + declined;
    if (!census.complete() || declined > 0) {
      syncReportService.recordIncomplete();
    }

    boolean sweep = !seenUexVehicleIds.isEmpty() && census.complete() && skipped == 0;
    if (!sweep) {
      log.warn(
          "Skipping vehicle orphan sweep — upstream ids={}, complete census={}, skipped rows={}",
          seenUexVehicleIds.size(),
          census.complete(),
          skipped);
    } else {
      int marked =
          chunkWriter.inNewTransaction(
              () -> shipTypeRepository.markUexDeletedExcept(seenUexVehicleIds, now));
      if (marked > 0) {
        log.info("Marked {} ship_type row(s) uex_deleted (no longer in UEX feed)", marked);
      }
    }
    syncReportService.recordSweep(sweep);

    log.info(
        "Finished UEX vehicle sync: {} added, {} updated, {} skipped", added, updated, skipped);
  }

  /**
   * Upserts a single UEX vehicle DTO into {@code ship_type}.
   *
   * @param dto inbound UEX row
   * @param now timestamp to stamp on the row
   * @param census validated source identities and UUID claims
   * @param runId current report run
   * @return whether the row was created, updated or rejected
   */
  private VehicleOutcome upsertVehicle(
      UexVehicleDto dto, Instant now, UexIdentityCensus census, UUID runId) {
    if (dto.id() == null || dto.id() <= 0 || !StringUtils.hasText(dto.name())) {
      log.debug("Skipping UEX vehicle with missing name (id={}, uuid={})", dto.id(), dto.uuid());
      return VehicleOutcome.SKIPPED;
    }

    UUID externalUuid = UexValues.parseUuid(dto.uuid());

    Optional<ShipType> existingOpt = shipTypeRepository.findByUexVehicleId(dto.id());
    if (existingOpt.isEmpty() && externalUuid != null && census.uniqueUuid(externalUuid)) {
      existingOpt =
          shipTypeRepository
              .findByExternalUuid(externalUuid)
              .filter(candidate ->
                  candidate.getUexVehicleId() == null
                      || !census.containsId(candidate.getUexVehicleId()));
    }
    if (existingOpt.isEmpty() && dto.id() != null) {
      List<ShipType> aliased = shipTypeRepository.findByUexAliasOfVehicleId(dto.id());
      if (aliased.size() == 1) {
        existingOpt = Optional.of(aliased.getFirst());
      }
    }
    if (existingOpt.isEmpty() && census.uniqueName(dto.name())) {
      existingOpt = shipTypeRepository.findByNameIgnoreCase(dto.name());
    }

    if (existingOpt.isPresent()) {
      ShipType candidate = existingOpt.orElseThrow();
      boolean sourceConflict =
          candidate.getUexVehicleId() != null
              && !dto.id().equals(candidate.getUexVehicleId())
              && (!census.complete() || census.containsId(candidate.getUexVehicleId()));
      boolean uuidConflict =
          candidate.getExternalUuid() != null
              && externalUuid != null
              && !candidate.getExternalUuid().equals(externalUuid);
      if (sourceConflict || uuidConflict) {
        syncReportService.logUexEvent(
            runId,
            SyncEventType.SOURCE_IDENTITY_CONFLICT,
            "ship_type",
            externalUuid,
            dto.name(),
            "UEX vehicle " + dto.id() + " conflicts with an existing source id or game UUID.");
        return VehicleOutcome.SKIPPED;
      }
    }

    ShipType shipType = existingOpt.orElseGet(ShipType::new);
    boolean isNew = existingOpt.isEmpty();
    if (isNew) {
      shipType.setName(dto.name());
      shipType.setSourceSystems(GameItemSourceSystem.UEX_ONLY);
    }

    if (externalUuid != null
        && shipType.getExternalUuid() == null
        && census.uniqueUuid(externalUuid)) {
      shipType.setExternalUuid(externalUuid);
    }
    if (dto.id() != null) {
      shipType.setUexVehicleId(dto.id());
    }

    P4kLedColumns led = new P4kLedColumns(shipType);
    Manufacturer manufacturer = resolveManufacturer(dto);
    if (manufacturer != null) {
      led.write(
          "manufacturer_id",
          shipType.getManufacturer(),
          manufacturer,
          shipType::setManufacturer,
          Manufacturer::getName);
    }

    applyVehicleFields(shipType, dto, led);
    if (!led.disagreements().isEmpty()) {
      syncReportService.logUexEvent(
          runId,
          SyncEventType.P4K_DISAGREEMENT,
          "ship_type",
          shipType.getP4kUuid(),
          dto.name(),
          String.join(" | ", led.disagreements()));
    }

    shipType.setUexSyncedAt(now);
    shipType.setUexDeletedAt(null);
    if (shipType.getSourceSystems() == GameItemSourceSystem.WIKI_ONLY) {
      shipType.setSourceSystems(GameItemSourceSystem.BOTH);
    }

    shipTypeRepository.save(shipType);
    return isNew ? VehicleOutcome.CREATED : VehicleOutcome.UPDATED;
  }

  /** Persistence outcome of one UEX vehicle. */
  private enum VehicleOutcome {
    CREATED,
    UPDATED,
    SKIPPED
  }

  /**
   * Copies every UEX-sourced column from the DTO onto the entity; a field missing upstream clears
   * the local value to {@code null}. Cargo, maximum crew and mass go through the P4K precedence
   * (REQ-P4K-031), and the minimum crew never stays above the kept maximum.
   *
   * @param shipType local row being updated
   * @param dto inbound DTO
   * @param led the row's P4K-led column writes
   */
  private static void applyVehicleFields(
      @NotNull ShipType shipType, @NotNull UexVehicleDto dto, @NotNull P4kLedColumns led) {
    shipType.setUexSlug(dto.slug());
    shipType.setNameFull(dto.nameFull());
    led.write("scu", shipType.getScu(), dto.scu(), shipType::setScu);
    UexValues.CrewRange crew = UexValues.parseCrew(dto.crew());
    shipType.setCrewMin(crew.min());
    led.write("crew_max", shipType.getCrewMax(), crew.max(), shipType::setCrewMax);
    shipType.capCrewMinAtCrewMax();
    led.write("mass", shipType.getMass(), dto.mass(), shipType::setMass);
    shipType.setWidth(dto.width());
    shipType.setHeight(dto.height());
    shipType.setLengthM(dto.length());
    shipType.setPadType(dto.padType());
    shipType.setFuelQuantum(dto.fuelQuantum());
    shipType.setFuelHydrogen(dto.fuelHydrogen());
    shipType.setContainerSizes(dto.containerSizes());
    shipType.setUrlStore(dto.urlStore());
    shipType.setUrlBrochure(dto.urlBrochure());
    shipType.setUrlHotsite(dto.urlHotsite());
    shipType.setUrlPhoto(dto.urlPhoto());
    shipType.setUrlVideo(dto.urlVideo());

    shipType.setIsAddon(UexValues.asBooleanOrNull(dto.isAddon()));
    shipType.setIsBoarding(UexValues.asBooleanOrNull(dto.isBoarding()));
    shipType.setIsBomber(UexValues.asBooleanOrNull(dto.isBomber()));
    shipType.setIsCargo(UexValues.asBooleanOrNull(dto.isCargo()));
    shipType.setIsCarrier(UexValues.asBooleanOrNull(dto.isCarrier()));
    shipType.setIsCivilian(UexValues.asBooleanOrNull(dto.isCivilian()));
    shipType.setIsConcept(UexValues.asBooleanOrNull(dto.isConcept()));
    shipType.setIsConstruction(UexValues.asBooleanOrNull(dto.isConstruction()));
    shipType.setIsDatarunner(UexValues.asBooleanOrNull(dto.isDatarunner()));
    shipType.setIsDocking(UexValues.asBooleanOrNull(dto.isDocking()));
    shipType.setIsEmp(UexValues.asBooleanOrNull(dto.isEmp()));
    shipType.setIsExploration(UexValues.asBooleanOrNull(dto.isExploration()));
    shipType.setIsGroundVehicle(UexValues.asBooleanOrNull(dto.isGroundVehicle()));
    shipType.setIsHangar(UexValues.asBooleanOrNull(dto.isHangar()));
    shipType.setIsIndustrial(UexValues.asBooleanOrNull(dto.isIndustrial()));
    shipType.setIsInterdiction(UexValues.asBooleanOrNull(dto.isInterdiction()));
    shipType.setIsLoadingDock(UexValues.asBooleanOrNull(dto.isLoadingDock()));
    shipType.setIsMedical(UexValues.asBooleanOrNull(dto.isMedical()));
    shipType.setIsMilitary(UexValues.asBooleanOrNull(dto.isMilitary()));
    shipType.setIsMining(UexValues.asBooleanOrNull(dto.isMining()));
    shipType.setIsPassenger(UexValues.asBooleanOrNull(dto.isPassenger()));
    shipType.setIsQed(UexValues.asBooleanOrNull(dto.isQed()));
    shipType.setIsQuantumCapable(UexValues.asBooleanOrNull(dto.isQuantumCapable()));
    shipType.setIsRacing(UexValues.asBooleanOrNull(dto.isRacing()));
    shipType.setIsRefinery(UexValues.asBooleanOrNull(dto.isRefinery()));
    shipType.setIsRefuel(UexValues.asBooleanOrNull(dto.isRefuel()));
    shipType.setIsRepair(UexValues.asBooleanOrNull(dto.isRepair()));
    shipType.setIsResearch(UexValues.asBooleanOrNull(dto.isResearch()));
    shipType.setIsSalvage(UexValues.asBooleanOrNull(dto.isSalvage()));
    shipType.setIsScanning(UexValues.asBooleanOrNull(dto.isScanning()));
    shipType.setIsScience(UexValues.asBooleanOrNull(dto.isScience()));
    shipType.setIsShowdownWinner(UexValues.asBooleanOrNull(dto.isShowdownWinner()));
    shipType.setIsSpaceship(UexValues.asBooleanOrNull(dto.isSpaceship()));
    shipType.setIsStarter(UexValues.asBooleanOrNull(dto.isStarter()));
    shipType.setIsStealth(UexValues.asBooleanOrNull(dto.isStealth()));
    shipType.setIsTractorBeam(UexValues.asBooleanOrNull(dto.isTractorBeam()));
  }

  /**
   * Resolves the vehicle's local manufacturer: by UEX {@code id_company} via the {@code
   * manufacturer_uex_company} alias table (ADR-0023), else by case-insensitive {@code
   * company_name}.
   *
   * @param dto inbound vehicle row
   * @return resolved manufacturer, or {@code null}, leaving the linked manufacturer unchanged
   */
  @Nullable
  private Manufacturer resolveManufacturer(UexVehicleDto dto) {
    if (dto.idCompany() != null && dto.idCompany() != 0) {
      Optional<Manufacturer> byId =
          manufacturerAliasRepository.findManufacturerByUexCompanyId(dto.idCompany());
      if (byId.isPresent()) {
        return byId.orElseThrow();
      }
    }
    if (!StringUtils.hasText(dto.companyName())) {
      return null;
    }
    return manufacturerRepository.findByNameIgnoreCase(dto.companyName()).orElse(null);
  }
}
