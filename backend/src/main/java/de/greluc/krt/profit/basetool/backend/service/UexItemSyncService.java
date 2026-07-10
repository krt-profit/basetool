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
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

/**
 * R2 UEX item sync. Iterates the {@code uex_category} reference table (game-related rows only),
 * calls {@code /items?id_category=<n>} per category, and upserts the rows into {@code game_item}.
 *
 * <p>Resolution chain per SC_WIKI_SYNC_PLAN.md §8.3.1, R2 subset (Wiki side not yet wired):
 *
 * <ol>
 *   <li>{@code byUexItemId(dto.id)} — fastest path on a re-sync of the same UEX catalogue.
 *   <li>{@code byExternalUuid(dto.uuid)} — joins any row Wiki may have written first (R4+); no-op
 *       in R2's first deploy because Wiki has not run yet.
 *   <li>{@code null} → create a new row stamped {@link GameItemSourceSystem#UEX_ONLY}.
 * </ol>
 *
 * <p>Kind derivation table per §6.3.1 — driven by the row's {@link UexCategory#getSection()}:
 *
 * <ul>
 *   <li>{@code "Armor"} → {@link GameItemKind#ARMOR}
 *   <li>{@code "Clothing"} or {@code "Undersuits"} → {@link GameItemKind#CLOTHING}
 *   <li>{@code "Personal Weapons"} → {@link GameItemKind#WEAPON} (or {@link
 *       GameItemKind#WEAPON_ATTACHMENT} when category name contains {@code "Attachments"})
 *   <li>{@code "Vehicle Weapons"} → {@link GameItemKind#VEHICLE_WEAPON}
 *   <li>{@code "Liveries"} or {@code "Flair"} → {@link GameItemKind#GENERIC}
 *   <li>{@code "Systems"}, {@code "Utility"}, {@code "Avionics"}, {@code "Propulsion"}, {@code
 *       "Module"}, {@code "Technology"} → {@link GameItemKind#VEHICLE_ITEM}
 *   <li>everything else → {@link GameItemKind#GENERIC}
 * </ul>
 *
 * <p>Orphan handling: rows whose {@code uex_item_id} no longer appears in any category response get
 * their {@code uex_deleted_at} stamped via {@link
 * GameItemRepository#markUexDeletedExcept(java.util.Collection, Instant)}. The sweep is gated on a
 * non-empty seen-id set so a sync that fails on every category never wipes the local catalogue.
 *
 * <p><strong>Per-item isolation (REQ-DATA-004).</strong> Each item is upserted in its own {@code
 * REQUIRES_NEW} transaction via {@link #upsertItemWithinTransaction(UexItemDto, UexCategory,
 * Instant)}, invoked through the {@link #self} proxy. UEX ships several distinct item ids sharing
 * one in-game {@code uuid} (a base weapon and its skins — e.g. ids 879/5457/5458 all carry the
 * MaxLift tractor-beam uuid), so an insert can collide with the {@code uk_game_item_external_uuid}
 * UNIQUE constraint. Before per-item isolation that single violation poisoned the one big
 * transaction's Hibernate session, so every <em>subsequent</em> item's autoflush re-threw the dead
 * insert and the whole run rolled back (observed in prod: 3376 cascade failures, no {@code
 * Finished} line). With a dedicated nested transaction per item a colliding row rolls back only
 * itself, the caller's {@code catch} skips it, and the rest of the catalogue still commits.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UexItemSyncService {

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

  /**
   * Runs the full UEX item sync: ensures the category reference table is fresh, then walks every
   * game-related category. Empty UEX responses short-circuit per category without wiping local
   * data.
   *
   * <p>Returns the number of {@code game_item} rows upserted this run — the same {@code upserted}
   * tally logged in the {@link SyncEventType#SYNC_RUN_SUMMARY} event. {@link UexScheduler} feeds it
   * into {@code basetool_scheduled_job_items_total{job="uex_sync"}} via {@code
   * TaskMetrics.recordCounting} so a catalogue outage that returns empty responses (0 upserts)
   * while the sweep still "succeeds" is visible through the {@code SyncZeroItems} alert (#1041 item
   * 2).
   *
   * @return the number of {@code game_item} rows upserted across all categories this run
   */
  @Transactional
  public int syncItems() {
    log.info("Starting synchronization of UEX items...");
    // final: the run id is captured at the start of the run but first used in the summary event at
    // the end, past the category loop — VariableDeclarationUsageDistance allows the gap when final.
    final UUID runId = syncReportService.beginRun();
    List<UexCategory> categories = categoryRefService.syncCategories();

    Set<Integer> seenUexItemIds = new HashSet<>();
    int categoriesProcessed = 0;
    int itemsProcessed = 0;
    int itemsCreated = 0;
    int sharedUuidDeclined = 0;
    Instant now = Instant.now();

    for (UexCategory category : categories) {
      if (!Boolean.TRUE.equals(category.getIsGameRelated())) {
        continue;
      }
      if (!"item".equalsIgnoreCase(category.getType())) {
        continue;
      }
      List<UexItemDto> dtos = uexClient.getItemsForCategory(category.getId());
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
          GameItem item = self.getObject().upsertItemWithinTransaction(dto, category, now);
          if (item != null) {
            itemsProcessed++;
            if (item.getCreatedAt() == null || item.getCreatedAt().equals(item.getUpdatedAt())) {
              itemsCreated++;
            }
            if (item.getUexItemId() != null) {
              seenUexItemIds.add(item.getUexItemId());
            }
            // A row that keeps external_uuid null although UEX shipped a parseable uuid is a
            // shared-uuid sibling whose uuid another game_item already owns — the expected,
            // permanent steady state for a base item + its skins (REQ-DATA-005). Tally these so
            // the run summary reports one aggregate count instead of a per-row WARN on every sync
            // (issue #1205).
            if (item.getExternalUuid() == null && parseUuid(dto.uuid()) != null) {
              sharedUuidDeclined++;
            }
          }
        } catch (Exception e) {
          log.error("Failed to process UEX item dto: {}", dto, e);
        }
      }
    }

    int marked = 0;
    if (seenUexItemIds.isEmpty()) {
      log.warn(
          "Skipping orphan sweep — no UEX item was processed across {} category response(s).",
          categoriesProcessed);
    } else {
      marked = gameItemRepository.markUexDeletedExcept(seenUexItemIds, now);
      if (marked > 0) {
        log.info("Marked {} game_item row(s) uex_deleted (no longer in UEX feed)", marked);
      }
    }

    // Decode the encoded query parameter for cleaner log output - the actual HTTP request was
    // built with the encoded form by UexClient.
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

    // One always-emitted SYNC_RUN_SUMMARY row per run, so the run shows on the admin UEX tab.
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
    return itemsProcessed;
  }

  /**
   * Upserts a single UEX item DTO into the {@code game_item} table in its own {@code REQUIRES_NEW}
   * transaction (REQ-DATA-004). The dedicated nested transaction is what makes a {@code
   * uk_game_item_external_uuid} collision — two UEX item ids sharing one in-game uuid — roll back
   * only this row instead of poisoning the whole run's session. Must be invoked through the {@link
   * #self} proxy; a direct {@code this} call would be self-invocation and skip the new transaction.
   *
   * <p>Belt-and-braces against that same shared-uuid case: when an existing row (resolved by {@code
   * uex_item_id}) would have its still-null {@code external_uuid} backfilled with a uuid a
   * <em>different</em> row already owns, this leaves {@code external_uuid} null rather than letting
   * the write hit {@code uk_game_item_external_uuid}. The row keeps its {@code uex_item_id} key and
   * every other UEX column still syncs, so the loser sibling no longer fails every run
   * (REQ-DATA-005).
   *
   * @param dto inbound UEX row
   * @param category resolved category for kind derivation + FK
   * @param now timestamp to stamp on the row
   * @return the persisted entity, or {@code null} if the DTO was unusable
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public GameItem upsertItemWithinTransaction(UexItemDto dto, UexCategory category, Instant now) {
    if (dto.id() == null || !StringUtils.hasText(dto.name())) {
      log.debug("Skipping UEX item with missing id/name: {}", dto);
      return null;
    }

    GameItem item = resolveExistingItem(dto);
    boolean newRow = (item == null);
    if (newRow) {
      item = new GameItem();
      item.setSourceSystems(GameItemSourceSystem.UEX_ONLY);
    }

    UUID externalUuid = parseUuid(dto.uuid());
    if (item.getExternalUuid() == null && externalUuid != null) {
      // R2 first write for a row UEX exposes with a UUID (R3 slug-fallback may later backfill rows
      // where UEX returned an empty uuid — the ~30% case). Claim the uuid only if no other row
      // already owns it: UEX gives a base weapon and its skins ONE shared in-game uuid (e.g. ids
      // 879/5457/5458) while external_uuid is UNIQUE. A brand-new row's uuid is free by
      // construction (resolveExistingItem just missed on it), but a row resolved by uex_item_id can
      // be asked to backfill a uuid a *different* row already holds — setting it would hit
      // uk_game_item_external_uuid (the prod failure on item 4752, the "Pulse Greycat Laser Pistol"
      // skin). Leave external_uuid null in that case so the row keeps its uex_item_id key and every
      // other UEX column still syncs (REQ-DATA-005).
      Optional<GameItem> uuidOwner =
          newRow ? Optional.empty() : gameItemRepository.findByExternalUuid(externalUuid);
      if (uuidOwner.isPresent()) {
        GameItem owner = uuidOwner.orElseThrow();
        // Expected, permanent steady state — not a fault: UEX gives a base item and its skins one
        // shared in-game uuid, but external_uuid is UNIQUE, so this sibling can never own it.
        // Logged at DEBUG (not WARN) because it recurs every sync and no admin action can resolve
        // it; the caller reports one aggregate sharedUuidDeclined count in the run summary (#1205).
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
      // UEX provided a UUID that disagrees with the one already stored — keep the existing key
      // (Wiki side may have written it) and log so the admin can investigate.
      log.warn(
          "UEX item {} carries uuid={} but local row already has external_uuid={}",
          dto.id(),
          externalUuid,
          item.getExternalUuid());
    }

    item.setName(dto.name());
    // §6.3.1 more-specific-wins: never downgrade a kind a previous (Wiki or UEX) pass already set
    // to something specific. A cross-listed paint that Wiki filed as VEHICLE_ITEM must not become
    // GENERIC just because UEX catalogues the same external_uuid under a Liveries category. New
    // rows start at GENERIC, so the merge leaves the freshly-derived kind intact.
    item.setKind(GameItemKind.mergeMoreSpecific(item.getKind(), deriveKind(category)));
    item.setManufacturer(resolveManufacturer(dto));
    item.setUexItemId(dto.id());
    item.setUexSlug(dto.slug());
    item.setUexCategory(category);
    item.setUexCompanyId(dto.idCompany());
    item.setUexVehicleId(dto.idVehicle());
    item.setLinkedShipType(resolveLinkedShipType(dto));
    item.setUexColor(dto.color());
    item.setUexColor2(dto.color2());
    item.setUexQuality(dto.quality());
    item.setUexUrlStore(dto.urlStore());
    item.setUexScreenshot(dto.screenshot());
    item.setIsExclusivePledge(asBoolean(dto.isExclusivePledge()));
    item.setIsExclusiveSubscriber(asBoolean(dto.isExclusiveSubscriber()));
    item.setIsExclusiveConcierge(asBoolean(dto.isExclusiveConcierge()));
    item.setUexIsCommodity(asBoolean(dto.isCommodity()));
    item.setUexIsHarvestable(asBoolean(dto.isHarvestable()));
    item.setUexNotification(dto.notification());
    item.setUexSyncedAt(now);
    item.setUexDeletedAt(null);
    item.setUexGameVersionSeen(dto.gameVersion());

    // Promote source_systems UEX_ONLY -> BOTH when Wiki has already written this row (R4+).
    if (item.getSourceSystems() == GameItemSourceSystem.WIKI_ONLY) {
      item.setSourceSystems(GameItemSourceSystem.BOTH);
    }

    return gameItemRepository.save(item);
  }

  /**
   * Resolves a candidate {@link GameItem} for the inbound DTO. Order: by UEX item id (fastest
   * re-resolution path), then by Wiki-shared external UUID (no-op in R2 first deploy), then {@code
   * null}.
   *
   * @param dto inbound UEX row
   * @return existing row if matched; {@code null} otherwise
   */
  private GameItem resolveExistingItem(UexItemDto dto) {
    if (dto.id() != null) {
      Optional<GameItem> byUex = gameItemRepository.findByUexItemId(dto.id());
      if (byUex.isPresent()) {
        return byUex.orElseThrow();
      }
    }
    UUID externalUuid = parseUuid(dto.uuid());
    if (externalUuid != null) {
      Optional<GameItem> byUuid = gameItemRepository.findByExternalUuid(externalUuid);
      if (byUuid.isPresent()) {
        return byUuid.orElseThrow();
      }
    }
    return null;
  }

  /**
   * Looks up the local manufacturer row by UEX company id via the {@code manufacturer_uex_company}
   * alias table (fast path — covers every id-variant of a brand UEX duplicates, ADR-0023), falling
   * back to a case-insensitive name match. Returns {@code null} when the company can't be resolved
   * — the row is still persisted; the FK stays NULL and the admin can fix it via {@code
   * /admin/material-aliases} once that surface is generalised (post-R2).
   *
   * @param dto inbound UEX row
   * @return resolved manufacturer, or {@code null}
   */
  private Manufacturer resolveManufacturer(UexItemDto dto) {
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
   * @return resolved ship type, or {@code null} if {@code id_vehicle} is 0 / unknown
   */
  private ShipType resolveLinkedShipType(UexItemDto dto) {
    if (dto.idVehicle() == null || dto.idVehicle() == 0) {
      return null;
    }
    return shipTypeRepository.findByUexVehicleId(dto.idVehicle()).orElse(null);
  }

  /**
   * Maps the row's category to a {@link GameItemKind} per the §6.3.1 table. The decision is driven
   * by the section first; for Personal Weapons the subcategory name also distinguishes weapons from
   * attachments.
   *
   * @param category resolved category for the row
   * @return derived kind, or {@link GameItemKind#GENERIC} if no specific match applies
   */
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

  /**
   * Parses a UEX-emitted UUID string. UEX returns an empty string for ~30% of rows (Avionics,
   * Decorations, Liveries, Armor); this method returns {@code null} for those instead of throwing.
   *
   * @param raw raw UUID string from the DTO
   * @return parsed UUID, or {@code null} for empty / blank / malformed input
   */
  private static UUID parseUuid(String raw) {
    if (!StringUtils.hasText(raw)) {
      return null;
    }
    try {
      return UUID.fromString(raw.trim());
    } catch (IllegalArgumentException e) {
      log.debug("Skipping malformed UEX uuid '{}': {}", raw, e.getMessage());
      return null;
    }
  }

  /**
   * Normalises UEX's integer 0/1 flag into a {@link Boolean}.
   *
   * @param flag UEX-style 0/1 integer
   * @return {@code true} iff {@code flag} equals 1; {@code null} when input is {@code null}
   */
  private static Boolean asBoolean(Integer flag) {
    if (flag == null) {
      return null;
    }
    return flag == 1;
  }
}
