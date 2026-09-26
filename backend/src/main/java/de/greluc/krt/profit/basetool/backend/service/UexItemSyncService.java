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

import de.greluc.krt.profit.basetool.backend.dto.uex.UexItemDto;
import de.greluc.krt.profit.basetool.backend.integration.UexClient;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.GameItemKind;
import de.greluc.krt.profit.basetool.backend.model.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.SyncEventType;
import de.greluc.krt.profit.basetool.backend.model.SyncSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.UexCategory;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.ManufacturerRepository;
import de.greluc.krt.profit.basetool.backend.repository.ManufacturerUexCompanyRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.UexKeyRef;
import de.greluc.krt.profit.basetool.backend.support.UexValues;
import de.greluc.krt.profit.basetool.logging.LogSafe;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

/**
 * Syncs UEX items into {@code game_item}, walking {@code /items?id_category=<n>} for every
 * game-related {@code uex_category}.
 *
 * <p>An item resolves by UEX item id, then by external UUID, else is created as {@link
 * GameItemSourceSystem#UEX_ONLY}. Items missing from every response are marked UEX-deleted via
 * {@link GameItemRepository#markUexDeletedExcept(java.util.Collection, Instant)}, but only when
 * every category fetch was complete (REQ-DATA-014). Each item is upserted in its own transaction,
 * so a colliding row rolls back alone (REQ-DATA-004).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UexItemSyncService {

  /** Maximum length of an upstream item name in log lines, logged through {@link LogSafe}. */
  private static final int MAX_NAME_LOG_LENGTH = 64;

  private final UexClient uexClient;
  private final UexCategoryRefService categoryRefService;
  private final GameItemRepository gameItemRepository;
  private final ManufacturerRepository manufacturerRepository;
  private final ManufacturerUexCompanyRepository manufacturerAliasRepository;
  private final ShipTypeRepository shipTypeRepository;
  private final SyncReportService syncReportService;

  /**
   * Self-reference, resolved lazily so each per-item upsert runs through the Spring transaction
   * proxy. Calling {@link #upsertItemWithinTransaction(UexItemDto, UexCategory, Instant)} via
   * {@code this} would be self-invocation and run in the {@code syncItems} transaction instead of
   * opening the {@code REQUIRES_NEW} one, re-introducing the session-poisoning cascade.
   */
  private final ObjectProvider<UexItemSyncService> self;

  /** Runs the preload read and the orphan sweep in transactions of their own (BE-PERF-09). */
  private final SyncChunkWriter chunkWriter;

  /**
   * Runs the full UEX item sync: refreshes the category table, then walks every game-related
   * category. Holds no transaction itself; an empty response leaves a category's local data intact.
   *
   * @return the number of {@code game_item} rows upserted this run, or the live UEX catalogue size
   *     when nothing was upserted because the catalogue was unchanged ({@code 304})
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public int syncItems() {
    log.info("Starting synchronization of UEX items...");
    final UUID runId = syncReportService.beginRun();
    List<UexCategory> categories = categoryRefService.syncCategories();

    Set<Integer> seenUexItemIds = new HashSet<>();
    int categoriesProcessed = 0;
    int itemsProcessed = 0;
    int itemsCreated = 0;
    int sharedUuidDeclined = 0;
    boolean anyCategoryUnchanged = false;
    int incompleteCategories = 0;
    Instant now = Instant.now();
    ItemLookups lookups = chunkWriter.inNewTransaction(this::loadItemLookups);

    for (UexCategory category : categories) {
      if (!Boolean.TRUE.equals(category.getIsGameRelated())) {
        continue;
      }
      if (!"item".equalsIgnoreCase(category.getType())) {
        continue;
      }
      UexClient.FetchResult<UexItemDto> fetched = uexClient.getItemsForCategory(category.getId());
      if (!fetched.complete()) {
        incompleteCategories++;
      }
      if (fetched.notModified()) {
        anyCategoryUnchanged = true;
        log.debug(
            "UEX category {} ({}/{}) unchanged since last sync (304 Not Modified)",
            category.getId(),
            category.getSection(),
            category.getName());
        continue;
      }
      List<UexItemDto> dtos = fetched.data();
      if (dtos.isEmpty()) {
        log.debug(
            "No items received for UEX category {} ({}/{})",
            category.getId(),
            category.getSection(),
            category.getName());
        continue;
      }
      categoriesProcessed++;
      for (UexItemDto dto : dtos) {
        try {
          GameItem item = self.getObject().upsertItemWithinTransaction(dto, category, now, lookups);
          if (item != null) {
            itemsProcessed++;
            if (item.getCreatedAt() == null || item.getCreatedAt().equals(item.getUpdatedAt())) {
              itemsCreated++;
            }
            if (item.getUexItemId() != null) {
              seenUexItemIds.add(item.getUexItemId());
            }
            if (item.getExternalUuid() == null && UexValues.parseUuid(dto.uuid()) != null) {
              sharedUuidDeclined++;
            }
          }
        } catch (Exception e) {
          log.error(
              "Failed to process UEX item dto (id={}, uuid={}, name='{}')",
              dto.id(),
              dto.uuid(),
              LogSafe.text(dto.name(), MAX_NAME_LOG_LENGTH),
              e);
        }
      }
    }

    int marked = 0;
    if (seenUexItemIds.isEmpty()) {
      log.warn(
          "Skipping orphan sweep — no UEX item was processed across {} category response(s).",
          categoriesProcessed);
    } else if (incompleteCategories > 0) {
      log.warn(
          "Skipping orphan sweep — {} item(s) seen but {} category fetch(es) came back incomplete"
              + " (failed, non-ok envelope or 304), so the seen-set is not a census of the"
              + " catalogue and a sweep would soft-delete rows that were merely never fetched.",
          seenUexItemIds.size(),
          incompleteCategories);
    } else {
      marked =
          chunkWriter.inNewTransaction(
              () -> gameItemRepository.markUexDeletedExcept(seenUexItemIds, now));
      if (marked > 0) {
        log.info("Marked {} game_item row(s) uex_deleted (no longer in UEX feed)", marked);
      }
    }

    String reportLabel = UriUtils.decode("UEX item sync", "UTF-8");
    log.info(
        "Finished {}: {} categories visited, {} items upserted ({} new, {} updated), {} shared-uuid"
            + " sibling(s) kept external_uuid null",
        reportLabel,
        categoriesProcessed,
        itemsProcessed,
        itemsCreated,
        itemsProcessed - itemsCreated,
        sharedUuidDeclined);

    syncReportService.logUexEvent(
        runId,
        SyncEventType.SYNC_RUN_SUMMARY,
        "game_item",
        null,
        null,
        "categories=%d, upserted=%d, created=%d, updated=%d, uexDeleted=%d, sharedUuidDeclined=%d"
            .formatted(
                categoriesProcessed,
                itemsProcessed,
                itemsCreated,
                itemsProcessed - itemsCreated,
                marked,
                sharedUuidDeclined));
    syncReportService.pruneRuns(SyncSourceSystem.UEX);

    if (itemsProcessed == 0 && anyCategoryUnchanged) {
      long liveCatalogue = gameItemRepository.countLiveUexItems();
      log.info(
          "UEX item sync upserted no rows but the catalogue was unchanged (at least one category"
              + " returned 304 Not Modified) — reporting live catalogue size {} for the items"
              + " metric instead of 0.",
          liveCatalogue);
      return (int) liveCatalogue;
    }
    return itemsProcessed;
  }

  /**
   * Upserts a single UEX item into {@code game_item} in its own {@code REQUIRES_NEW} transaction,
   * so a {@code uk_game_item_external_uuid} collision rolls back only this row (REQ-DATA-004). Must
   * be invoked through the {@link #self} proxy.
   *
   * <p>An {@code external_uuid} that another row already owns is not backfilled; the row still
   * syncs its other columns.
   *
   * @param dto inbound UEX row
   * @param category resolved category for kind derivation + FK
   * @param now timestamp to stamp on the row
   * @return the persisted entity, or {@code null} if the DTO was unusable
   */
  @Nullable
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public GameItem upsertItemWithinTransaction(UexItemDto dto, UexCategory category, Instant now) {
    return upsertItemWithinTransaction(dto, category, now, null);
  }

  /**
   * Same as {@link #upsertItemWithinTransaction(UexItemDto, UexCategory, Instant)}, resolving the
   * manufacturer and ship type from preloaded id maps.
   *
   * @param dto inbound UEX row
   * @param category resolved category for kind derivation + FK
   * @param now timestamp to stamp on the row
   * @param lookups the run's preloaded id maps, or {@code null} for per-row lookups
   * @return the persisted entity, or {@code null} if the DTO was unusable
   */
  @Nullable
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public GameItem upsertItemWithinTransaction(
      UexItemDto dto, UexCategory category, Instant now, @Nullable ItemLookups lookups) {
    if (dto.id() == null || !StringUtils.hasText(dto.name())) {
      log.debug(
          "Skipping UEX item with missing id/name (id={}, uuid={}, name='{}')",
          dto.id(),
          dto.uuid(),
          LogSafe.text(dto.name(), MAX_NAME_LOG_LENGTH));
      return null;
    }

    GameItem item = resolveExistingItem(dto);
    boolean newRow = (item == null);
    if (newRow) {
      item = new GameItem();
      item.setSourceSystems(GameItemSourceSystem.UEX_ONLY);
    }

    UUID externalUuid = UexValues.parseUuid(dto.uuid());
    if (item.getExternalUuid() == null && externalUuid != null) {
      Optional<GameItem> uuidOwner =
          newRow ? Optional.empty() : gameItemRepository.findByExternalUuid(externalUuid);
      if (uuidOwner.isPresent()) {
        GameItem owner = uuidOwner.orElseThrow();
        log.debug(
            "UEX item {} shares uuid={} with game_item id={} (uex_item_id={}); keeping"
                + " external_uuid null to avoid uk_game_item_external_uuid collision",
            dto.id(),
            externalUuid,
            owner.getId(),
            owner.getUexItemId());
      } else {
        item.setExternalUuid(externalUuid);
      }
    } else if (item.getExternalUuid() != null
        && externalUuid != null
        && !item.getExternalUuid().equals(externalUuid)) {
      log.warn(
          "UEX item {} carries uuid={} but local row already has external_uuid={}",
          dto.id(),
          externalUuid,
          item.getExternalUuid());
    }

    item.setName(dto.name());
    item.setKind(GameItemKind.mergeMoreSpecific(item.getKind(), deriveKind(category)));
    item.setManufacturer(resolveManufacturer(dto, lookups));
    item.setUexItemId(dto.id());
    item.setUexSlug(dto.slug());
    item.setUexCategory(category);
    item.setUexCompanyId(dto.idCompany());
    item.setUexVehicleId(dto.idVehicle());
    item.setLinkedShipType(resolveLinkedShipType(dto, lookups));
    item.setUexColor(dto.color());
    item.setUexColor2(dto.color2());
    item.setUexQuality(dto.quality());
    item.setUexUrlStore(dto.urlStore());
    item.setUexScreenshot(dto.screenshot());
    item.setIsExclusivePledge(UexValues.asBooleanOrNull(dto.isExclusivePledge()));
    item.setIsExclusiveSubscriber(UexValues.asBooleanOrNull(dto.isExclusiveSubscriber()));
    item.setIsExclusiveConcierge(UexValues.asBooleanOrNull(dto.isExclusiveConcierge()));
    item.setUexIsCommodity(UexValues.asBooleanOrNull(dto.isCommodity()));
    item.setUexIsHarvestable(UexValues.asBooleanOrNull(dto.isHarvestable()));
    item.setUexNotification(dto.notification());
    item.setUexSyncedAt(now);
    item.setUexDeletedAt(null);
    item.setUexGameVersionSeen(dto.gameVersion());

    if (item.getSourceSystems() == GameItemSourceSystem.WIKI_ONLY) {
      item.setSourceSystems(GameItemSourceSystem.BOTH);
    }

    return gameItemRepository.save(item);
  }

  /**
   * Resolves the existing {@link GameItem} for the inbound DTO by UEX item id, then by external
   * UUID.
   *
   * @param dto inbound UEX row
   * @return existing row if matched; {@code null} otherwise
   */
  @Nullable
  private GameItem resolveExistingItem(UexItemDto dto) {
    if (dto.id() != null) {
      Optional<GameItem> byUex = gameItemRepository.findByUexItemId(dto.id());
      if (byUex.isPresent()) {
        return byUex.orElseThrow();
      }
    }
    UUID externalUuid = UexValues.parseUuid(dto.uuid());
    if (externalUuid != null) {
      Optional<GameItem> byUuid = gameItemRepository.findByExternalUuid(externalUuid);
      if (byUuid.isPresent()) {
        return byUuid.orElseThrow();
      }
    }
    return null;
  }

  /**
   * Resolves the manufacturer by UEX company id through the {@code manufacturer_uex_company} alias
   * table (ADR-0023), falling back to a case-insensitive name match.
   *
   * <p>With {@code lookups} the result is a {@code getReferenceById} proxy and no query runs.
   *
   * @param dto inbound UEX row
   * @param lookups the run's preloaded id maps, or {@code null} for per-row lookups
   * @return resolved manufacturer, or {@code null}; the item is persisted either way
   */
  @Nullable
  private Manufacturer resolveManufacturer(UexItemDto dto, @Nullable ItemLookups lookups) {
    if (lookups != null) {
      UUID id = null;
      if (dto.idCompany() != null && dto.idCompany() != 0) {
        id = lookups.manufacturerByCompanyId().get(dto.idCompany());
      }
      if (id == null && StringUtils.hasText(dto.companyName())) {
        id = lookups.manufacturerByLowerName().get(dto.companyName().toLowerCase(Locale.ROOT));
      }
      return id == null ? null : manufacturerRepository.getReferenceById(id);
    }
    if (dto.idCompany() != null && dto.idCompany() != 0) {
      Optional<Manufacturer> byId =
          manufacturerAliasRepository.findManufacturerByUexCompanyId(dto.idCompany());
      if (byId.isPresent()) {
        return byId.orElseThrow();
      }
    }
    if (StringUtils.hasText(dto.companyName())) {
      return manufacturerRepository.findByNameIgnoreCase(dto.companyName()).orElse(null);
    }
    return null;
  }

  /**
   * Looks up the local {@link ShipType} for vehicle-bound items (paints, components carrying {@code
   * id_vehicle}).
   *
   * @param dto inbound UEX row
   * @param lookups the run's preloaded id maps, or {@code null} for a per-row lookup
   * @return resolved ship type, or {@code null} if {@code id_vehicle} is 0 / unknown
   */
  @Nullable
  private ShipType resolveLinkedShipType(UexItemDto dto, @Nullable ItemLookups lookups) {
    if (dto.idVehicle() == null || dto.idVehicle() == 0) {
      return null;
    }
    if (lookups != null) {
      UUID id = lookups.shipTypeByVehicleId().get(dto.idVehicle());
      return id == null ? null : shipTypeRepository.getReferenceById(id);
    }
    return shipTypeRepository.findByUexVehicleId(dto.idVehicle()).orElse(null);
  }

  /**
   * Reads the item sync's three lookup maps, one query each: manufacturer id by UEX company id (the
   * alias table), manufacturer id by lower-cased name, and ship-type id by UEX vehicle id.
   *
   * @return the lookups, holding ids only
   */
  @NotNull
  ItemLookups loadItemLookups() {
    Map<Integer, UUID> byCompany = new HashMap<>();
    for (UexKeyRef ref : manufacturerAliasRepository.findCompanyRefs()) {
      byCompany.putIfAbsent(ref.getUexId(), ref.getId());
    }
    Map<String, UUID> byName = new HashMap<>();
    for (ManufacturerRepository.NameRef ref : manufacturerRepository.findNameRefs()) {
      if (ref.getName() != null) {
        byName.putIfAbsent(ref.getName().toLowerCase(Locale.ROOT), ref.getId());
      }
    }
    Map<Integer, UUID> byVehicle = new HashMap<>();
    for (UexKeyRef ref : shipTypeRepository.findUexVehicleRefs()) {
      byVehicle.putIfAbsent(ref.getUexId(), ref.getId());
    }
    return new ItemLookups(byCompany, byName, byVehicle);
  }

  /**
   * The item sync's preloaded id maps, valid for the whole run because manufacturers and ship types
   * are synced before the items.
   *
   * @param manufacturerByCompanyId manufacturer id by UEX company id (every alias)
   * @param manufacturerByLowerName manufacturer id by lower-cased name
   * @param shipTypeByVehicleId ship-type id by UEX vehicle id
   */
  public record ItemLookups(
      @NotNull Map<Integer, UUID> manufacturerByCompanyId,
      @NotNull Map<String, UUID> manufacturerByLowerName,
      @NotNull Map<Integer, UUID> shipTypeByVehicleId) {}

  /**
   * Maps the row's category to a {@link GameItemKind} by its section and, for Personal Weapons, by
   * whether the category name marks attachments.
   *
   * @param category resolved category for the row
   * @return derived kind, or {@link GameItemKind#GENERIC} if no specific match applies
   */
  @NotNull
  static GameItemKind deriveKind(UexCategory category) {
    if (category == null || category.getSection() == null) {
      return GameItemKind.GENERIC;
    }
    String section = category.getSection().toLowerCase(Locale.ROOT);
    String name = category.getName() == null ? "" : category.getName().toLowerCase(Locale.ROOT);
    return switch (section) {
      case "armor" -> GameItemKind.ARMOR;
      case "clothing", "undersuits" -> GameItemKind.CLOTHING;
      case "personal weapons" ->
          name.contains("attachment") ? GameItemKind.WEAPON_ATTACHMENT : GameItemKind.WEAPON;
      case "vehicle weapons" -> GameItemKind.VEHICLE_WEAPON;
      case "liveries", "flair" -> GameItemKind.GENERIC;
      case "systems", "utility", "avionics", "propulsion", "module", "technology" ->
          GameItemKind.VEHICLE_ITEM;
      default -> GameItemKind.GENERIC;
    };
  }
}
