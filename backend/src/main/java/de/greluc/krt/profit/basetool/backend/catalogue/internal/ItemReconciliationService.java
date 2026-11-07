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

import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintStatProperty;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemReassignmentParticipant;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemStat;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemStatRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unifies verified source duplicates while archiving both source snapshots and retaining local ids.
 */
@Service
@RequiredArgsConstructor
public class ItemReconciliationService {
  private final GameItemRepository items;
  private final GameItemRedirectRepository redirects;
  private final CatalogueItemReassignmentRepository recipes;
  private final GameItemPriceRepository prices;
  private final GameItemStatRepository stats;
  private final List<GameItemReassignmentParticipant> participants;
  private final SyncReportService reports;
  private final P4kMergeConflictRecorder p4kConflicts;

  /**
   * Consolidates a uniquely matched uuid-less UEX row into an existing Wiki identity.
   *
   * <p>The merge respects P4K identity (REQ-P4K-007): rows with different P4K GUIDs stay apart and
   * go to the P4K review list; a GUID only the retired row holds moves onto the survivor.
   *
   * @param wikiItem managed Wiki item
   * @param uexItemId uuid-less source row's local id
   * @param runId source report run
   * @param incoming current Wiki facts that independently identify the candidate
   * @return the surviving managed item, or null when the candidate no longer qualifies
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public @Nullable GameItem mergeExisting(
      @NotNull GameItem wikiItem,
      @NotNull UUID uexItemId,
      @NotNull UUID runId,
      @NotNull WikiIdentity incoming) {
    if (wikiItem.getId().equals(uexItemId)) {
      return null;
    }
    List<GameItem> locked = items.lockReconciliationRows(List.of(wikiItem.getId(), uexItemId));
    GameItem source =
        locked.stream().filter(item -> item.getId().equals(uexItemId)).findFirst().orElse(null);
    GameItem target =
        locked.stream()
            .filter(item -> item.getId().equals(wikiItem.getId()))
            .findFirst()
            .orElse(null);
    if (source == null
        || target == null
        || source.getExternalUuid() != null
        || source.getSourceSystems() != GameItemSourceSystem.UEX_ONLY
        || target.getExternalUuid() == null
        || target.getUexItemId() != null
        || !ItemIdentityCompatibility.matches(
            source, incoming.kind(), incoming.manufacturer(), incoming.className())
        || !ItemIdentityCompatibility.matches(
            source, target.getKind(), target.getManufacturer(), target.getClassName())) {
      return null;
    }
    String sourceName = source.getUexName() == null ? source.getName() : source.getUexName();
    String expectedName = UexIdentityCensus.nameKey(incoming.name());
    if (expectedName == null || !expectedName.equals(UexIdentityCensus.nameKey(sourceName))) {
      return null;
    }
    Integer uexId = source.getUexItemId();
    if (uexId == null) {
      return null;
    }
    UUID retiredGuid = source.getP4kUuid();
    UUID survivorGuid = target.getP4kUuid();
    if (retiredGuid != null && survivorGuid != null && !retiredGuid.equals(survivorGuid)) {
      p4kConflicts.record(source, retiredGuid, target, survivorGuid);
      reports.logScwikiEvent(
          runId,
          SyncEventType.SOURCE_IDENTITY_CONFLICT,
          "game_item",
          target.getExternalUuid(),
          target.getName(),
          "retiredItem=" + uexItemId + ";survivorItem=" + target.getId() + ";p4kGuidConflict");
      return null;
    }
    final String archive = redirects.snapshot(uexItemId, target.getId());
    final P4kParticipation moved =
        retiredGuid != null && survivorGuid == null
            ? P4kParticipation.detach(source, retiredGuid)
            : null;
    source.setUexItemId(null);
    items.saveAndFlush(source);
    copyUexColumns(source, target);
    if (moved != null) {
      moved.attachTo(target);
    }
    target.setUexItemId(uexId);
    items.saveAndFlush(target);
    for (GameItemReassignmentParticipant participant : participants) {
      participant.reassignGameItem(uexItemId, target.getId());
    }
    recipes.blueprints(uexItemId, target.getId());
    recipes.ingredients(uexItemId, target.getId());
    movePrices(source, target);
    moveStats(source, target);
    redirects.repoint(uexItemId, target.getId());
    GameItemRedirect redirect = new GameItemRedirect();
    redirect.setId(uexItemId);
    redirect.setCanonicalItem(target);
    redirect.setRetiredSnapshot(archive);
    redirects.saveAndFlush(redirect);
    items.delete(source);
    items.flush();
    reports.logScwikiEvent(
        runId,
        SyncEventType.SOURCE_RECORDS_MERGED,
        "game_item",
        target.getExternalUuid(),
        target.getName(),
        "retiredItem=" + uexItemId + ";survivorItem=" + target.getId());
    return target;
  }

  /**
   * Current source facts used to validate a prefetched name candidate under the catalogue row
   * locks.
   *
   * @param name Wiki source display name
   * @param kind most-specific kind established by the source endpoint
   * @param manufacturer resolved Wiki source manufacturer
   * @param className engine class supplied by the current Wiki payload
   */
  public record WikiIdentity(
      @Nullable String name,
      @NotNull GameItemKind kind,
      @Nullable Manufacturer manufacturer,
      @Nullable String className) {}

  /**
   * The P4K columns of a row (REQ-P4K-005, REQ-P4K-007, REQ-P4K-017), carried from a retired row
   * onto a survivor that the P4K never matched.
   *
   * @param p4kUuid the P4K GUID
   * @param nameKey the {@code global.ini} name key
   * @param nameDe the German name the P4K wrote (REQ-P4K-023)
   * @param syncedAt the last P4K touch
   * @param deletedAt the P4K soft-delete marker
   * @param seenRunId the run that last matched the row
   * @param values the P4K's values of the attributes it leads
   */
  private record P4kParticipation(
      @NotNull UUID p4kUuid,
      @Nullable String nameKey,
      @Nullable String nameDe,
      @Nullable Instant syncedAt,
      @Nullable Instant deletedAt,
      @Nullable UUID seenRunId,
      @NotNull P4kItemValues values) {

    /**
     * Takes the P4K columns off a row; the GUID is unique, so it must leave before it lands.
     *
     * @param row the row that holds a P4K GUID
     * @param guid the row's P4K GUID
     * @return the removed columns
     */
    static @NotNull P4kParticipation detach(@NotNull GameItem row, @NotNull UUID guid) {
      final P4kParticipation taken =
          new P4kParticipation(
              guid,
              row.getNameKey(),
              row.getNameDe(),
              row.getP4kSyncedAt(),
              row.getP4kDeletedAt(),
              row.getP4kSeenRunId(),
              P4kItemValues.of(row));
      row.setP4kUuid(null);
      row.setNameKey(null);
      row.setNameDe(null);
      row.setP4kSyncedAt(null);
      row.setP4kDeletedAt(null);
      row.setP4kSeenRunId(null);
      P4kItemValues.NONE.storeOn(row);
      return taken;
    }

    /**
     * Writes the columns onto a row; the P4K's values lead the row's displayed attributes.
     *
     * @param row the survivor
     */
    void attachTo(@NotNull GameItem row) {
      row.setP4kUuid(p4kUuid);
      row.setNameKey(nameKey);
      row.setNameDe(nameDe);
      row.setP4kSyncedAt(syncedAt);
      row.setP4kDeletedAt(deletedAt);
      row.setP4kSeenRunId(seenRunId);
      values.storeOn(row);
      values.leadOnto(row);
    }
  }

  private static void copyUexColumns(GameItem from, GameItem to) {
    to.setUexName(from.getUexName() == null ? from.getName() : from.getUexName());
    if (to.getP4kName() == null) {
      to.setName(from.getName());
    }
    if (to.getP4kManufacturer() == null && from.getManufacturer() != null) {
      to.setManufacturer(from.getManufacturer());
    }
    to.setKind(GameItemKind.mergeMoreSpecific(to.getKind(), from.getKind()));
    to.setUexSlug(from.getUexSlug());
    to.setUexCategory(from.getUexCategory());
    to.setUexCompanyId(from.getUexCompanyId());
    to.setUexVehicleId(from.getUexVehicleId());
    to.setLinkedShipType(from.getLinkedShipType());
    to.setUexColor(from.getUexColor());
    to.setUexColor2(from.getUexColor2());
    to.setUexQuality(from.getUexQuality());
    to.setUexUrlStore(from.getUexUrlStore());
    to.setUexScreenshot(from.getUexScreenshot());
    to.setIsExclusivePledge(from.getIsExclusivePledge());
    to.setIsExclusiveSubscriber(from.getIsExclusiveSubscriber());
    to.setIsExclusiveConcierge(from.getIsExclusiveConcierge());
    to.setUexIsCommodity(from.getUexIsCommodity());
    to.setUexIsHarvestable(from.getUexIsHarvestable());
    to.setUexNotification(from.getUexNotification());
    to.setUexGameVersionSeen(from.getUexGameVersionSeen());
    to.setUexSyncedAt(from.getUexSyncedAt());
    to.setUexDeletedAt(from.getUexDeletedAt());
    if (to.getSourceSystems() == GameItemSourceSystem.WIKI_ONLY) {
      to.setSourceSystems(GameItemSourceSystem.BOTH);
    }
  }

  private void movePrices(GameItem from, GameItem to) {
    Comparator<Long> modified = Comparator.nullsFirst(Long::compareTo);
    Comparator<Instant> synced = Comparator.nullsFirst(Instant::compareTo);
    Map<UUID, GameItemPrice> targetPrices =
        prices.findByGameItemId(to.getId()).stream()
            .collect(Collectors.toMap(price -> price.getTerminal().getId(), Function.identity()));
    for (GameItemPrice source : prices.findByGameItemId(from.getId())) {
      GameItemPrice target = targetPrices.get(source.getTerminal().getId());
      if (target == null) {
        source.setGameItem(to);
        prices.save(source);
        targetPrices.put(source.getTerminal().getId(), source);
      } else {
        int dateOrder = modified.compare(source.getDateModified(), target.getDateModified());
        if (dateOrder > 0
            || (dateOrder == 0
                && synced.compare(source.getUexSyncedAt(), target.getUexSyncedAt()) > 0)) {
          target.setPriceBuy(source.getPriceBuy());
          target.setPriceSell(source.getPriceSell());
          target.setPriceRent(source.getPriceRent());
          target.setStatusBuy(source.getStatusBuy());
          target.setStatusSell(source.getStatusSell());
          target.setDateModified(source.getDateModified());
          target.setGameVersion(source.getGameVersion());
          target.setUexSyncedAt(source.getUexSyncedAt());
          prices.save(target);
        }
        prices.delete(source);
      }
    }
    prices.flush();
  }

  private void moveStats(GameItem from, GameItem to) {
    Map<BlueprintStatProperty, GameItemStat> targetStats =
        stats.findByGameItemId(to.getId()).stream()
            .collect(Collectors.toMap(GameItemStat::getProperty, Function.identity()));
    for (GameItemStat source : stats.findByGameItemId(from.getId())) {
      if (targetStats.containsKey(source.getProperty())) {
        stats.delete(source);
      } else {
        source.setGameItem(to);
        stats.save(source);
      }
    }
    stats.flush();
  }
}
