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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.UexCategory;
import de.greluc.krt.profit.basetool.backend.catalogue.api.UexKeyRef;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexItemDto;
import de.greluc.krt.profit.basetool.logging.LogSafe;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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

/**
 * Syncs UEX items into {@code game_item}, walking {@code /items?id_category=<n>} for every
 * game-related {@code uex_category}.
 *
 * <p>An item resolves by UEX item id, then an unambiguous external UUID or exact source name,
 * otherwise it is created as {@link GameItemSourceSystem#UEX_ONLY}. Verified source-id
 * reassignments release their old bindings before writes, after the complete feed validates their
 * UUIDs. Items missing from every response are marked UEX-deleted via {@link
 * GameItemRepository#markUexDeletedExcept(java.util.Collection, Instant)}, but only when every
 * category fetch was complete (REQ-DATA-014). Each item is upserted in its own transaction, so a
 * colliding row rolls back alone (REQ-DATA-004).
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
  private final ScWikiProperties scWikiProperties;

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
   * @return the number of {@code game_item} rows upserted this run
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public int syncItems() {
    log.info("Starting synchronization of UEX items...");
    final UUID runId = syncReportService.beginRun();
    UexCategoryRefService.CategorySyncResult categoryResult =
        categoryRefService.syncCategoriesResult();
    List<UexCategory> categories = categoryResult.categories();

    final RunTally tally = new RunTally();
    int categoriesProcessed = 0;
    int incompleteCategories = 0;
    final Instant now = Instant.now();
    ItemLookups lookups = chunkWriter.inNewTransaction(this::loadItemLookups);
    List<DeferredItem> inputs = new ArrayList<>();

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
        inputs.add(new DeferredItem(dto, category));
      }
    }
    UexIdentityCensus census =
        UexIdentityCensus.of(
            inputs,
            input -> input.dto().id(),
            input -> input.dto().uuid(),
            input -> input.dto().name(),
            categoryResult.complete() && incompleteCategories == 0);
    lookups = lookups.withCensus(census);
    if (!census.complete()) {
      syncReportService.recordIncomplete();
    }
    for (DeferredItem input : inputs) {
      UUID uuid = UexValues.parseUuid(input.dto().uuid());
      if (census.uniqueUuid(uuid) && lookups.isReassignedId(input.dto().id(), uuid)) {
        if (releaseReassignedId(input.dto())) {
          tally.idsReassigned++;
        } else {
          tally.failed++;
          syncReportService.recordFailed(1);
        }
      }
    }
    for (DeferredItem input : inputs) {
      upsertAndTally(input.dto(), input.category(), now, lookups, tally);
    }
    Set<Integer> seenUexItemIds = census.ids();
    int itemsProcessed = tally.itemsProcessed;
    int itemsCreated = tally.itemsCreated;
    int sharedUuidDeclined = tally.sharedUuidDeclined;

    int marked = 0;
    if (seenUexItemIds.isEmpty()) {
      log.warn(
          "Skipping orphan sweep — no UEX item was processed across {} category response(s).",
          categoriesProcessed);
    } else if (!census.complete() || tally.failed > 0 || tally.rejected > 0) {
      log.warn(
          "Skipping orphan sweep — {} upstream ids seen; complete census={}, failed writes={},"
              + " rejected rows={}",
          seenUexItemIds.size(),
          census.complete(),
          tally.failed,
          tally.rejected);
    } else {
      marked =
          chunkWriter.inNewTransaction(
              () -> gameItemRepository.markUexDeletedExcept(seenUexItemIds, now));
      if (marked > 0) {
        log.info("Marked {} game_item row(s) uex_deleted (no longer in UEX feed)", marked);
      }
    }
    syncReportService.recordSweep(
        !seenUexItemIds.isEmpty() && census.complete() && tally.failed == 0 && tally.rejected == 0);

    log.info(
        "Finished UEX item sync: {} categories visited, {} items upserted ({} new, {} updated), {}"
            + " shared-uuid sibling(s) kept external_uuid null, {} reassigned UEX id(s) rebound by"
            + " UUID",
        categoriesProcessed,
        itemsProcessed,
        itemsCreated,
        itemsProcessed - itemsCreated,
        sharedUuidDeclined,
        tally.idsReassigned);

    syncReportService.logUexEvent(
        runId,
        SyncEventType.SYNC_RUN_SUMMARY,
        "game_item",
        null,
        null,
        ("categories=%d, upserted=%d, created=%d, updated=%d, uexDeleted=%d, sharedUuidDeclined=%d,"
                + " idsReassigned=%d")
            .formatted(
                categoriesProcessed,
                itemsProcessed,
                itemsCreated,
                itemsProcessed - itemsCreated,
                marked,
                sharedUuidDeclined,
                tally.idsReassigned));
    syncReportService.pruneRuns(SyncSourceSystem.UEX);

    return itemsProcessed;
  }

  /**
   * Releases a reassigned UEX id through the {@link #self} proxy. A failure is logged and leaves
   * the id where it is, so the following upsert falls back to the id match.
   *
   * @param dto inbound UEX row whose id moved to a different item
   * @return {@code true} if the id was released
   */
  private boolean releaseReassignedId(UexItemDto dto) {
    try {
      self.getObject().releaseReassignedUexItemIdWithinTransaction(dto);
      return true;
    } catch (RuntimeException e) {
      log.error("Failed to release reassigned UEX item id {}", dto.id(), e);
      return false;
    }
  }

  /**
   * Upserts one DTO through the {@link #self} proxy and adds the outcome to {@code tally}; a
   * failing row is logged and skipped (REQ-DATA-004).
   *
   * @param dto inbound UEX row
   * @param category resolved category for kind derivation + FK
   * @param now timestamp to stamp on the row
   * @param lookups the run's preloaded id maps
   * @param tally the run's counters
   */
  private void upsertAndTally(
      UexItemDto dto, UexCategory category, Instant now, ItemLookups lookups, RunTally tally) {
    try {
      ItemWriteOutcome result =
          self.getObject().writeItemWithinTransaction(dto, category, now, lookups);
      GameItem item = result.item();
      if (item == null) {
        tally.rejected++;
        syncReportService.recordIncomplete();
        syncReportService.recordSkipped(1);
        return;
      }
      tally.itemsProcessed++;
      syncReportService.recordApplied(1);
      if (result.created()) {
        tally.itemsCreated++;
      }
      if (item.getExternalUuid() == null && UexValues.parseUuid(dto.uuid()) != null) {
        tally.sharedUuidDeclined++;
      }
    } catch (Exception e) {
      tally.failed++;
      syncReportService.recordFailed(1);
      log.error(
          "Failed to process UEX item dto (id={}, uuid={}, name='{}')",
          dto.id(),
          dto.uuid(),
          LogSafe.text(dto.name(), MAX_NAME_LOG_LENGTH),
          e);
    }
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
    return writeItemWithinTransaction(dto, category, now, lookups).item();
  }

  /**
   * Writes one UEX item and reports whether this write inserted a new catalogue row.
   *
   * @param dto inbound UEX row
   * @param category resolved UEX category
   * @param now this run's source timestamp
   * @param lookups preloaded references and upstream identity census
   * @return the persisted item and its creation verdict, or an empty item for rejected input
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public @NotNull ItemWriteOutcome writeItemWithinTransaction(
      UexItemDto dto, UexCategory category, Instant now, @Nullable ItemLookups lookups) {
    if (dto.id() == null || dto.id() <= 0 || !StringUtils.hasText(dto.name())) {
      log.debug(
          "Skipping UEX item with missing id/name (id={}, uuid={}, name='{}')",
          dto.id(),
          dto.uuid(),
          LogSafe.text(dto.name(), MAX_NAME_LOG_LENGTH));
      return new ItemWriteOutcome(null, false);
    }

    GameItem item = resolveExistingItem(dto, category, lookups);
    final boolean created = item == null;
    if (item == null) {
      item = new GameItem();
      item.setSourceSystems(GameItemSourceSystem.UEX_ONLY);
    }

    UUID externalUuid = UexValues.parseUuid(dto.uuid());
    if (item.getExternalUuid() == null
        && externalUuid != null
        && (lookups == null || lookups.census().uniqueUuid(externalUuid))) {
      final UUID writtenId = item.getId();
      Optional<GameItem> uuidOwner =
          gameItemRepository
              .findBySourceUuid(externalUuid)
              .filter(owner -> !Objects.equals(owner.getId(), writtenId));
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

    item.setUexName(dto.name());
    GameItemNamePrecedence.applyUexName(item, dto.name());
    item.setKind(GameItemKind.mergeMoreSpecific(item.getKind(), deriveKind(category)));
    Manufacturer manufacturer = resolveManufacturer(dto, lookups);
    if (manufacturer != null && item.getP4kManufacturer() == null) {
      item.setManufacturer(manufacturer);
    }
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

    return new ItemWriteOutcome(gameItemRepository.save(item), created);
  }

  /**
   * One isolated UEX write, including whether it inserted the catalogue row.
   *
   * @param item the persisted item, or {@code null} when input was rejected
   * @param created whether the item was newly inserted
   */
  public record ItemWriteOutcome(@Nullable GameItem item, boolean created) {}

  /**
   * Clears {@code uex_item_id} on the row that holds the DTO's UEX id, in its own transaction, so a
   * following {@link #upsertItemWithinTransaction} resolves the DTO by its UUID. Used only for an
   * id UEX has reassigned to another item (see {@link ItemLookups#isReassignedId}). Must be invoked
   * through the {@link #self} proxy.
   *
   * @param dto inbound UEX row whose id moved to a different item
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void releaseReassignedUexItemIdWithinTransaction(@NotNull UexItemDto dto) {
    gameItemRepository
        .findByUexItemId(dto.id())
        .ifPresent(row -> {
          log.warn(
              "UEX item id {} now carries uuid={}; releasing it from game_item id={}"
                  + " (external_uuid={}) and matching by UUID",
              dto.id(),
              dto.uuid(),
              row.getId(),
              row.getExternalUuid());
          row.setUexItemId(null);
          gameItemRepository.saveAndFlush(row);
        });
  }

  /**
   * Resolves the source id first, then a unique unclaimed UUID or compatible exact Wiki source
   * name.
   *
   * @param dto inbound UEX row
   * @param category source category used to reject incompatible name matches
   * @param lookups this run's source-id and UUID census
   * @return existing row if matched; {@code null} otherwise
   */
  @Nullable
  private GameItem resolveExistingItem(
      UexItemDto dto, UexCategory category, @Nullable ItemLookups lookups) {
    if (dto.id() != null) {
      Optional<GameItem> byUex = gameItemRepository.findByUexItemId(dto.id());
      if (byUex.isPresent()) {
        return byUex.orElseThrow();
      }
    }
    UUID externalUuid = UexValues.parseUuid(dto.uuid());
    if (externalUuid != null && (lookups == null || lookups.census().uniqueUuid(externalUuid))) {
      Optional<GameItem> byUuid = gameItemRepository.findBySourceUuid(externalUuid);
      if (byUuid.isPresent()) {
        GameItem candidate = byUuid.orElseThrow();
        if (candidate.getUexItemId() == null || candidate.getUexItemId().equals(dto.id())) {
          return candidate;
        }
        if (lookups != null
            && lookups.census().complete()
            && !lookups.census().containsId(candidate.getUexItemId())) {
          return candidate;
        }
      }
    }
    if (externalUuid == null
        && !StringUtils.hasText(dto.uuid())
        && lookups != null
        && lookups.census().uniqueName(dto.name())
        && Boolean.TRUE.equals(scWikiProperties.reconcileUuidlessByName())) {
      List<GameItem> candidates = gameItemRepository.findWikiNameCandidates(dto.name());
      if (candidates.size() == 1) {
        GameItem candidate = candidates.getFirst();
        Manufacturer manufacturer = resolveManufacturer(dto, lookups);
        if (candidate.getUexItemId() == null
            && ItemIdentityCompatibility.matches(
                candidate, deriveKind(category), manufacturer, null)) {
          return candidate;
        }
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
    Map<Integer, UUID> uuidByUexItemId = new HashMap<>();
    Set<UUID> knownUuids = new HashSet<>();
    for (GameItemRepository.ExternalUuidRef ref : gameItemRepository.findExternalUuidRefs()) {
      knownUuids.add(ref.getExternalUuid());
      if (ref.getUexItemId() != null) {
        uuidByUexItemId.put(ref.getUexItemId(), ref.getExternalUuid());
      }
    }
    return new ItemLookups(
        byCompany,
        byName,
        byVehicle,
        uuidByUexItemId,
        knownUuids,
        UexIdentityCensus.of(
            List.<UexItemDto>of(), UexItemDto::id, UexItemDto::uuid, UexItemDto::name, false));
  }

  /**
   * The item sync's preloaded id maps, valid for the whole run because manufacturers and ship types
   * are synced before the items.
   *
   * @param manufacturerByCompanyId manufacturer id by UEX company id (every alias)
   * @param manufacturerByLowerName manufacturer id by lower-cased name
   * @param shipTypeByVehicleId ship-type id by UEX vehicle id
   * @param externalUuidByUexItemId stored {@code external_uuid} by UEX item id, for rows with both
   * @param knownExternalUuids every {@code external_uuid} already stored on a {@code game_item}
   * @param census the validated source-id and UUID census of this run
   */
  public record ItemLookups(
      @NotNull Map<Integer, UUID> manufacturerByCompanyId,
      @NotNull Map<String, UUID> manufacturerByLowerName,
      @NotNull Map<Integer, UUID> shipTypeByVehicleId,
      @NotNull Map<Integer, UUID> externalUuidByUexItemId,
      @NotNull Set<UUID> knownExternalUuids,
      @NotNull UexIdentityCensus census) {

    /**
     * Binds the preloaded references to the completed upstream identity census.
     *
     * @param census the run's source-id and UUID census
     * @return the references with the run's identity evidence
     */
    private ItemLookups withCensus(@NotNull UexIdentityCensus census) {
      return new ItemLookups(
          manufacturerByCompanyId,
          manufacturerByLowerName,
          shipTypeByVehicleId,
          externalUuidByUexItemId,
          knownExternalUuids,
          census);
    }

    /**
     * Whether UEX has moved {@code uexItemId} to a different item: the row holding the id stores
     * another UUID, and a second row already stores the incoming one. A UUID no row knows yet is
     * not taken as proof, since UEX has served wrong UUIDs on otherwise correct ids.
     *
     * @param uexItemId the inbound UEX item id
     * @param uuid the inbound item's parsed UUID
     * @return {@code true} if the id should be released and the row resolved by its UUID
     */
    public boolean isReassignedId(@Nullable Integer uexItemId, @Nullable UUID uuid) {
      if (uexItemId == null || uuid == null) {
        return false;
      }
      UUID stored = externalUuidByUexItemId.get(uexItemId);
      return stored != null && !stored.equals(uuid) && knownExternalUuids.contains(uuid);
    }
  }

  /**
   * An item held back to the end of the run because its UEX id looks reassigned.
   *
   * @param dto inbound UEX row
   * @param category the category it was fetched under
   */
  private record DeferredItem(@NotNull UexItemDto dto, @NotNull UexCategory category) {}

  /** Mutable counters of one item-sync run. */
  private static final class RunTally {

    /** Rows upserted this run. */
    private int itemsProcessed;

    /** Rows created this run. */
    private int itemsCreated;

    /** Rows that kept {@code external_uuid} null because another row owns the UUID. */
    private int sharedUuidDeclined;

    /** UEX ids released from their old row because UEX moved them to another item. */
    private int idsReassigned;

    /** Rows whose isolated write failed. */
    private int failed;

    /** Source rows rejected before a database write. */
    private int rejected;
  }

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
