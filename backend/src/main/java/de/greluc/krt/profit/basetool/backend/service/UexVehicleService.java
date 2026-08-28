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

import de.greluc.krt.profit.basetool.backend.dto.uex.UexVehicleDto;
import de.greluc.krt.profit.basetool.backend.integration.UexClient;
import de.greluc.krt.profit.basetool.backend.model.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.repository.ManufacturerRepository;
import de.greluc.krt.profit.basetool.backend.repository.ManufacturerUexCompanyRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.support.LogSafe;
import de.greluc.krt.profit.basetool.backend.support.UexValues;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * R2 UEX vehicle sync. Replaces the pre-R2 name-only matcher with the §8.5 UUID-first chain and
 * populates the 36 capability flags / dimensions / fuel / urls / English description that the V111
 * migration added to {@code ship_type}.
 *
 * <p>Resolution chain per SC_WIKI_SYNC_PLAN.md §8.5:
 *
 * <ol>
 *   <li>{@code findByExternalUuid(dto.uuid)} — strongest signal (Plan §3.6's 241-test invariant).
 *   <li>{@code findByUexVehicleId(dto.id)} — picks up rows the previous sync stamped without a UUID
 *       (~31% of UEX vehicles have no UUID).
 *   <li>{@code findByNameIgnoreCase(dto.name)} — legacy fallback that <b>backfills both</b> {@code
 *       external_uuid} (when UEX provides one) and {@code uex_vehicle_id} on hit. This is R2's
 *       substitute for the planned V112 data migration — the first sync after R2 deploys sets both
 *       columns on every match by name, and subsequent syncs never re-enter this path.
 *   <li>create a new row stamped {@link GameItemSourceSystem#UEX_ONLY}.
 * </ol>
 *
 * <p>R9 Step 2: the legacy synthesized {@code description} column is no longer written — readers
 * source the ship-type description from {@code descriptionEn} / {@code descriptionDe} instead (the
 * column is dropped in R9 Step 4). Neither of those two is written <em>here</em>: UEX serves no
 * description at all, so both come from the SC-Wiki vehicle sync (REQ-DATA-015).
 *
 * <p>Empty UEX response short-circuits without wiping local data. Orphan handling via {@link
 * ShipTypeRepository#markUexDeletedExcept(java.util.Collection, Instant)} gated on a non-empty
 * seen-id set.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UexVehicleService {

  /**
   * Cap for the upstream-supplied vehicle name in log lines. UEX is a third party we do not
   * control, so the value is untrusted free text and goes through {@link LogSafe} first; 64
   * characters comfortably fit any real ship name.
   */
  private static final int MAX_NAME_LOG_LENGTH = 64;

  private final UexClient uexClient;
  private final ShipTypeRepository shipTypeRepository;
  private final ManufacturerRepository manufacturerRepository;
  private final ManufacturerUexCompanyRepository manufacturerAliasRepository;

  /** Pulls the UEX vehicle catalogue and upserts each row. */
  @Transactional
  public void syncVehicles() {
    log.info("Starting synchronization of UEX vehicles (ships)...");
    UexClient.FetchResult<UexVehicleDto> fetched = uexClient.getVehicles();
    if (fetched.notModified()) {
      // Catalogue byte-identical to the last run: nothing to upsert, and the orphan sweep below is
      // skipped with it — every ship_type it would tombstone is still in the (unchanged) feed.
      log.info("UEX vehicle catalogue unchanged since the last sync (304) — nothing to import.");
      return;
    }
    List<UexVehicleDto> vehicles = fetched.data();
    if (vehicles.isEmpty()) {
      log.warn("No vehicles received from UEX API. Aborting synchronization.");
      return;
    }

    Instant now = Instant.now();
    Set<Integer> seenUexVehicleIds = new HashSet<>();
    int added = 0;
    int updated = 0;
    int skipped = 0;
    for (UexVehicleDto dto : vehicles) {
      try {
        boolean isNew = upsertVehicle(dto, now, seenUexVehicleIds);
        if (isNew) {
          added++;
        } else {
          updated++;
        }
      } catch (Exception e) {
        log.error(
            "Failed to process UEX vehicle dto (id={}, name='{}')",
            dto.id(),
            LogSafe.text(dto.name(), MAX_NAME_LOG_LENGTH),
            e);
        skipped++;
      }
    }

    if (seenUexVehicleIds.isEmpty()) {
      log.warn("Skipping orphan sweep — no UEX vehicle was processed successfully.");
    } else {
      int marked = shipTypeRepository.markUexDeletedExcept(seenUexVehicleIds, now);
      if (marked > 0) {
        log.info("Marked {} ship_type row(s) uex_deleted (no longer in UEX feed)", marked);
      }
    }

    log.info(
        "Finished UEX vehicle sync: {} added, {} updated, {} skipped", added, updated, skipped);
  }

  /**
   * Upserts a single UEX vehicle DTO into {@code ship_type}.
   *
   * @param dto inbound UEX row
   * @param now timestamp to stamp on the row
   * @param seenUexVehicleIds accumulator for the orphan sweep
   * @return {@code true} when the row was newly inserted
   */
  private boolean upsertVehicle(UexVehicleDto dto, Instant now, Set<Integer> seenUexVehicleIds) {
    if (!StringUtils.hasText(dto.name())) {
      log.debug("Skipping UEX vehicle with missing name (id={}, uuid={})", dto.id(), dto.uuid());
      return false;
    }

    UUID externalUuid = UexValues.parseUuid(dto.uuid());

    Optional<ShipType> existingOpt = Optional.empty();
    if (externalUuid != null) {
      existingOpt = shipTypeRepository.findByExternalUuid(externalUuid);
    }
    if (existingOpt.isEmpty() && dto.id() != null) {
      existingOpt = shipTypeRepository.findByUexVehicleId(dto.id());
    }
    if (existingOpt.isEmpty()) {
      existingOpt = shipTypeRepository.findByNameIgnoreCase(dto.name());
    }

    ShipType shipType = existingOpt.orElseGet(ShipType::new);
    boolean isNew = existingOpt.isEmpty();
    if (isNew) {
      shipType.setName(dto.name());
      shipType.setSourceSystems(GameItemSourceSystem.UEX_ONLY);
    }

    // Backfill the cross-source keys. The legacy name-fallback rows get external_uuid +
    // uex_vehicle_id
    // on this run; subsequent syncs never re-enter the name path.
    if (externalUuid != null && shipType.getExternalUuid() == null) {
      shipType.setExternalUuid(externalUuid);
    }
    if (dto.id() != null) {
      shipType.setUexVehicleId(dto.id());
      seenUexVehicleIds.add(dto.id());
    }

    Manufacturer manufacturer = resolveManufacturer(dto);
    if (manufacturer != null) {
      shipType.setManufacturer(manufacturer);
    }

    applyVehicleFields(shipType, dto);

    shipType.setUexSyncedAt(now);
    shipType.setUexDeletedAt(null);
    // Promote UEX_ONLY -> BOTH when Wiki already wrote this row (R4+).
    if (shipType.getSourceSystems() == GameItemSourceSystem.WIKI_ONLY) {
      shipType.setSourceSystems(GameItemSourceSystem.BOTH);
    }

    shipTypeRepository.save(shipType);
    return isNew;
  }

  /**
   * Copies every R2 column from the DTO onto the entity. Defensive: every setter receives the DTO
   * value (possibly {@code null}) so a missing field on the UEX side clears the local row to {@code
   * null} instead of stale-write surviving across schema migrations.
   *
   * @param shipType local row being updated
   * @param dto inbound DTO
   */
  private static void applyVehicleFields(ShipType shipType, UexVehicleDto dto) {
    shipType.setUexSlug(dto.slug());
    shipType.setNameFull(dto.nameFull());
    shipType.setScu(dto.scu());
    // UEX serves the crew complement as one compact string ("1", "1,2"), not as crew_min/crew_max
    // (REQ-DATA-015): binding those two decoded to null and cleared both columns on every run.
    UexValues.CrewRange crew = UexValues.parseCrew(dto.crew());
    shipType.setCrewMin(crew.min());
    shipType.setCrewMax(crew.max());
    shipType.setMass(dto.mass());
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
    // NOT written here, because UEX's /vehicles payload does not carry them (REQ-DATA-015):
    // mass_total, ore_capacity, max_medical_tier, health, shield_hp, url_wiki, description and
    // description_de. Writing them cleared eight columns outright and — for vehicle_inventory_scu
    // and description_en — undid what the SC-Wiki vehicle sync had filled in, every single run.

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
   * Looks up the local manufacturer row from the inbound vehicle DTO. Resolves by the UEX {@code
   * id_company} via the {@code manufacturer_uex_company} alias table first (so a brand UEX splits
   * across several company records — e.g. the ships sit on {@code 278 "Esperia Incorporation"}
   * while the items sit on {@code 87 "Esperia"} — still reunites on one manufacturer row,
   * ADR-0023), falling back to a case-insensitive {@code company_name} match for rows without an
   * id. Returns {@code null} when nothing matches — the linked manufacturer then stays whatever the
   * previous sync set.
   *
   * @param dto inbound vehicle row
   * @return resolved manufacturer, or {@code null}
   */
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
