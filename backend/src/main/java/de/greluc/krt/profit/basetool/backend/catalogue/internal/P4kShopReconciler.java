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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kClosedSet;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShopDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShopKind;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;

/**
 * Imports the catalogue's {@code shops} into {@code shop} (REQ-P4K-042, REQ-P4K-044): every record
 * with a key is matched by that key or added, the P4K's columns are mirrored onto it, its starmap
 * object is resolved by GUID, and the items of a known item list replace the shop's item rows. A
 * {@code null} list is unknown, so the shop keeps no item rows. A preview reports the same and
 * writes nothing; the terminal link is {@link P4kShopLinker}'s.
 */
@Component
@RequiredArgsConstructor
public class P4kShopReconciler {

  /** The kind this reconciler owns. */
  private static final P4kKind KIND = P4kKind.SHOP;

  /** The report field of the kind's closed value set. */
  private static final String KIND_FIELD = "kind";

  private final ShopRepository shopRepository;
  private final ShopItemRepository itemRepository;
  private final EntityManager entityManager;

  /**
   * Reconciles every shop of the run; a key the catalogue repeats is read once. Items and starmap
   * objects must be reconciled first.
   *
   * @param records the catalogue's shops
   * @param context the run
   * @return the shops of the run with their records, in catalogue order
   */
  @NotNull
  @Unmodifiable
  public List<Reconciled> reconcile(
      @NotNull List<P4kShopDto> records, @NotNull P4kImportContext context) {
    Map<UUID, Shop> byGuid = new HashMap<>();
    shopRepository.findAll().forEach(s -> byGuid.put(s.getP4kUuid(), s));
    P4kRowIds items = P4kRowIds.load(entityManager, P4kKind.ITEM, context);
    P4kRowIds objects = P4kRowIds.load(entityManager, P4kKind.STARMAP_OBJECT, context);
    Map<UUID, Set<UUID>> storedItems = storedItems();
    Map<UUID, Reconciled> run = new LinkedHashMap<>();
    for (P4kShopDto record : records) {
      String key = record.key();
      UUID guid = record.parsedGuid();
      if (key == null || guid == null || run.containsKey(guid)) {
        continue;
      }
      String rawKind = record.kind();
      if (rawKind != null && P4kClosedSet.isUnknown(P4kShopKind.class, rawKind)) {
        context.getReport().unknownValue(KIND, record, KIND_FIELD, rawKind);
      }
      Shop row = write(record, key, guid, objects.id(record.starmapObjectGuid()), byGuid, context);
      context.stamp(KIND, record, row, guid);
      run.put(guid, new Reconciled(record, row));
      if (context.isApply()) {
        relinkItems(row, itemIds(record, items), storedItems.getOrDefault(row.getId(), Set.of()));
      }
    }
    return List.copyOf(run.values());
  }

  /**
   * A shop of the run.
   *
   * @param record the record
   * @param row the stored or added shop
   */
  public record Reconciled(@NotNull P4kShopDto record, @NotNull Shop row) {}

  /**
   * Writes one record onto its stored shop, or adds the shop.
   *
   * @param record the record
   * @param key its key
   * @param guid its GUID, derived from the key
   * @param objectId the starmap object the record names, or {@code null}
   * @param byGuid every stored shop by GUID
   * @param context the run
   * @return the stored or added shop
   */
  @NotNull
  private Shop write(
      @NotNull P4kShopDto record,
      @NotNull String key,
      @NotNull UUID guid,
      @Nullable UUID objectId,
      @NotNull Map<UUID, Shop> byGuid,
      @NotNull P4kImportContext context) {
    Shop row = byGuid.get(guid);
    if (row == null) {
      Shop added = new Shop();
      added.setP4kUuid(guid);
      added.setShopKey(key);
      copy(record, objectId, added);
      context.getReport().added(KIND, record);
      if (context.isApply()) {
        shopRepository.save(added);
      }
      return added;
    }
    P4kRowWriter writer = new P4kRowWriter(context, KIND, record);
    writer.mirror(
        "entity_guid", row.getEntityGuid(), record.parsedEntityGuid(), row::setEntityGuid);
    writer.mirror("entity_name", row.getEntityName(), record.entityName(), row::setEntityName);
    writer.mirror(
        "registry_name", row.getRegistryName(), record.registryName(), row::setRegistryName);
    writer.mirror("star_system", row.getStarSystem(), record.system(), row::setStarSystem);
    writer.mirror("location_name", row.getLocationName(), record.location(), row::setLocationName);
    writer.mirror("kind", row.getKind(), kindOf(record), row::setKind);
    writer.mirror("brand", row.getBrand(), record.brand(), row::setBrand);
    writer.mirror(
        "commodity_exchange",
        row.isCommodityExchange(),
        Boolean.TRUE.equals(record.commodityExchange()),
        row::setCommodityExchange);
    writer.mirror("inventory", row.getInventory(), record.inventory(), row::setInventory);
    writer.mirror("starmap_object_id", row.getStarmapObjectId(), objectId, row::setStarmapObjectId);
    context.getReport().matched(KIND, writer.isChanged());
    return row;
  }

  /**
   * Copies a record's values onto an added shop.
   *
   * @param record the record
   * @param objectId the starmap object the record names, or {@code null}
   * @param row the added shop
   */
  private static void copy(@NotNull P4kShopDto record, @Nullable UUID objectId, @NotNull Shop row) {
    row.setEntityGuid(record.parsedEntityGuid());
    row.setEntityName(record.entityName());
    row.setRegistryName(record.registryName());
    row.setStarSystem(record.system());
    row.setLocationName(record.location());
    row.setKind(kindOf(record));
    row.setBrand(record.brand());
    row.setCommodityExchange(Boolean.TRUE.equals(record.commodityExchange()));
    row.setInventory(record.inventory());
    row.setStarmapObjectId(objectId);
  }

  /**
   * Reads a record's kind.
   *
   * @param record the record
   * @return the kind, or {@code null} when absent or unknown (REQ-P4K-003)
   */
  @Nullable
  private static P4kShopKind kindOf(@NotNull P4kShopDto record) {
    return P4kClosedSet.parse(P4kShopKind.class, record.kind());
  }

  /**
   * Resolves the items of a record's item list; a GUID without a {@code game_item} row is skipped.
   *
   * @param record the record
   * @param items the items by GUID
   * @return the item ids, empty for an unknown or empty list
   */
  @NotNull
  private static Set<UUID> itemIds(@NotNull P4kShopDto record, @NotNull P4kRowIds items) {
    Set<UUID> ids = new HashSet<>();
    if (record.itemGuids() != null) {
      for (String guid : record.itemGuids()) {
        UUID id = items.id(guid);
        if (id != null) {
          ids.add(id);
        }
      }
    }
    return ids;
  }

  /**
   * Reads the stored item rows, grouped by shop.
   *
   * @return the item ids per shop
   */
  @NotNull
  private Map<UUID, Set<UUID>> storedItems() {
    Map<UUID, Set<UUID>> stored = new HashMap<>();
    for (ShopItem link : itemRepository.findAll()) {
      stored.computeIfAbsent(link.getShopId(), _ -> new HashSet<>()).add(link.getGameItemId());
    }
    return stored;
  }

  /**
   * Sets the item rows of one shop: deletes those its list no longer names and adds the new ones.
   *
   * @param shop the shop, saved
   * @param items the items its list names
   * @param current its stored items
   */
  private void relinkItems(
      @NotNull Shop shop, @NotNull Set<UUID> items, @NotNull Set<UUID> current) {
    List<UUID> gone = new ArrayList<>();
    for (UUID itemId : current) {
      if (!items.contains(itemId)) {
        gone.add(itemId);
      }
    }
    if (!gone.isEmpty()) {
      itemRepository.deleteItems(shop.getId(), gone);
    }
    for (UUID itemId : items) {
      if (!current.contains(itemId)) {
        entityManager.persist(new ShopItem(shop.getId(), itemId));
      }
    }
  }
}
