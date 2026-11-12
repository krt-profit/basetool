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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.EarmarkTargets;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolEntryState;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolRestore;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolRestoreResult;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLotWritePlans.EntryRestore;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityComposition;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Shares named-row restore refusal and target resolution between planning and physical execution.
 */
@Component
@RequiredArgsConstructor
final class InventoryPoolRestoreDecisions {
  private static final double EPSILON = AllocationReductions.REDUCTION_EPSILON;
  private static final Pattern LOT_KEY_SEPARATOR = Pattern.compile("\\|");

  private final UserRepository users;
  private final OrgUnitRepository pools;
  private final MaterialRepository materials;
  private final GameItemRepository items;
  private final LocationRepository locations;
  private final OrgUnitMembershipQueryService memberships;
  private final EarmarkTargets.JobOrders orders;
  private final EarmarkTargets.Missions missions;

  /** The owning result and actual mutation decision for one named historical entry. */
  record Decision(
      PoolRestoreResult result,
      boolean changes,
      @Nullable Resolved target,
      @Nullable PoolEntryState targetState) {}

  /** Existing references resolved without exposing catalogue or ownership metadata to callers. */
  record Resolved(
      @Nullable Material material,
      @Nullable GameItem gameItem,
      Location location,
      @Nullable Integer quality,
      boolean stolen,
      User holder,
      OrgUnit pool) {
    /** Returns the exact physical destination represented by the resolved historical identity. */
    StockLot lot() {
      return new StockLot(material, gameItem, location, quality, stolen);
    }
  }

  /** Immutable target availability for the complete declared historical prefix. */
  record Targets(Map<PoolEntryState, Optional<Resolved>> entries) {
    Targets {
      entries = Map.copyOf(entries);
    }

    /** Reads one declared target without treating an unknown key as a missing catalogue entry. */
    @Nullable
    Resolved get(PoolEntryState state) {
      return Objects.requireNonNull(entries.get(state)).orElse(null);
    }
  }

  /** Every target reference and membership needed by a batch, read a bounded number of times. */
  private record References(
      Map<UUID, User> users,
      Map<UUID, OrgUnit> pools,
      Map<UUID, Material> materials,
      Map<UUID, GameItem> items,
      Map<UUID, Location> locations,
      Map<UUID, Set<UUID>> memberships,
      Set<UUID> orders,
      Set<UUID> missions) {}

  /** The scalar physical identity encoded in the historical lot key. */
  private record ParsedLot(
      @Nullable UUID materialId,
      @Nullable UUID gameItemId,
      UUID locationId,
      @Nullable Integer quality,
      boolean stolen) {}

  /** Resolves only targets whose current physical state reaches the owning target check. */
  Targets targets(
      List<PoolRestore> requests, Map<UUID, Optional<InventoryPoolRestoreFacts.Entry>> current) {
    Map<PoolEntryState, ParsedLot> valid = new LinkedHashMap<>();
    Map<PoolEntryState, Optional<Resolved>> resolved = new LinkedHashMap<>();
    for (PoolRestore request : requests) {
      PoolEntryState target = request.target();
      if (target == null
          || !asExpected(request, Objects.requireNonNull(current.get(request.entryId())))) {
        continue;
      }
      ParsedLot lot = parse(target.lotKey());
      if (lot == null) {
        resolved.put(target, Optional.empty());
      } else {
        valid.put(target, lot);
      }
    }
    if (!valid.isEmpty()) {
      References references = references(valid);
      valid.forEach((target, lot) ->
          resolved.put(target, Optional.ofNullable(resolve(target, lot, references))));
    }
    return new Targets(resolved);
  }

  /**
   * Decides the existing expected-state, donation and target-availability rules without a write.
   */
  Decision decide(
      EntryRestore intent, Optional<InventoryPoolRestoreFacts.Entry> current, Targets targets) {
    PoolRestore request = intent.request();
    if (!asExpected(request, current)) {
      return new Decision(PoolRestoreResult.CHANGED_AFTERWARDS, false, null, null);
    }
    PoolEntryState target = request.target();
    if (target == null) {
      if (current.isPresent() && current.get().row().donationReserved() > EPSILON) {
        return new Decision(PoolRestoreResult.CHANGED_AFTERWARDS, false, null, null);
      }
      return new Decision(PoolRestoreResult.RESTORED, current.isPresent(), null, null);
    }
    Resolved resolved = targets.get(target);
    if (resolved == null) {
      return new Decision(PoolRestoreResult.GONE, false, null, null);
    }
    PoolEntryState effective = fullTarget(target, current, intent.journalReference());
    if (current.isEmpty()) {
      return new Decision(PoolRestoreResult.RESTORED, true, resolved, effective);
    }
    var entry = current.get();
    if (entry.row().donationReserved() > target.amount() + EPSILON) {
      return new Decision(PoolRestoreResult.CHANGED_AFTERWARDS, false, null, null);
    }
    return new Decision(
        PoolRestoreResult.RESTORED,
        !sameContent(Objects.requireNonNull(entry.sharedState()), effective),
        resolved,
        effective);
  }

  /** Preserves physical presence and shared-entry identity in the expected-state comparison. */
  private static boolean asExpected(
      PoolRestore request, Optional<InventoryPoolRestoreFacts.Entry> current) {
    PoolEntryState expected = request.expected();
    if (expected == null) {
      return current.isEmpty();
    }
    PoolEntryState shared = current.isEmpty() ? null : current.get().sharedState();
    return shared != null && sameState(shared, expected);
  }

  /** Compares journaled identities and versions before comparing physical content. */
  private static boolean sameState(PoolEntryState left, PoolEntryState right) {
    return left.id().equals(right.id())
        && left.version() == right.version()
        && samePlacementAndAllocations(left, right)
        && (right.completeQuantity() != null
            ? right.completeQuantity().equals(left.completeQuantity())
            : right.quantityProvenance().isEmpty()
                || right.quantityProvenance().equals(left.quantityProvenance()));
  }

  /** Compares physical placement, quantity and original earmarks independently of row version. */
  static boolean sameContent(@NotNull PoolEntryState left, @NotNull PoolEntryState right) {
    return samePlacementAndAllocations(left, right)
        && Objects.equals(left.completeQuantity(), right.completeQuantity())
        && left.quantityProvenance().equals(right.quantityProvenance());
  }

  /** Compares the old snapshot's actual placement and allocation facts without inventing proof. */
  private static boolean samePlacementAndAllocations(PoolEntryState left, PoolEntryState right) {
    return left.holder().equals(right.holder())
        && left.poolUnitId().equals(right.poolUnitId())
        && left.lotKey().equals(right.lotKey())
        && Math.abs(left.amount() - right.amount()) <= EPSILON
        && sameAmounts(left.jobOrders(), right.jobOrders())
        && sameAmounts(left.missions(), right.missions());
  }

  /** Resolves absent old proof using surviving current facts and the real journal reference. */
  private static PoolEntryState fullTarget(
      PoolEntryState target,
      Optional<InventoryPoolRestoreFacts.Entry> current,
      UUID journalReference) {
    StockQuantityComposition complete = target.completeQuantity();
    if (complete == null) {
      complete =
          InventoryQuantityComposition.restoredLegacy(
              current
                  .map(InventoryPoolRestoreFacts.Entry::quantity)
                  .orElse(StockQuantityComposition.EMPTY),
              ScuAmounts.toQuanta(target.amount()),
              journalReference);
    }
    return new PoolEntryState(
        target.id(),
        target.holder(),
        target.poolUnitId(),
        target.lotKey(),
        target.amount(),
        target.version(),
        target.jobOrders(),
        target.missions(),
        complete.knownPhysical(),
        complete);
  }

  /** Compares all actual slice identities and their quantities within the owning tolerance. */
  private static boolean sameAmounts(Map<UUID, Double> left, Map<UUID, Double> right) {
    if (!left.keySet().equals(right.keySet())) {
      return false;
    }
    for (Map.Entry<UUID, Double> entry : left.entrySet()) {
      Double other = right.get(entry.getKey());
      if (entry.getValue() == null
          || other == null
          || Math.abs(entry.getValue() - other) > EPSILON) {
        return false;
      }
    }
    return true;
  }

  /** Loads catalogue, holder, pool and earmark availability once for the complete target set. */
  private References references(Map<PoolEntryState, ParsedLot> targets) {
    Set<UUID> holderIds = new HashSet<>();
    Set<UUID> poolIds = new HashSet<>();
    Set<UUID> materialIds = new HashSet<>();
    Set<UUID> itemIds = new HashSet<>();
    Set<UUID> locationIds = new HashSet<>();
    Set<UUID> orderIds = new HashSet<>();
    Set<UUID> missionIds = new HashSet<>();
    targets.forEach((target, lot) -> {
      holderIds.add(target.holder());
      poolIds.add(target.poolUnitId());
      if (lot.materialId() != null) {
        materialIds.add(lot.materialId());
      }
      if (lot.gameItemId() != null) {
        itemIds.add(lot.gameItemId());
      }
      locationIds.add(lot.locationId());
      orderIds.addAll(target.jobOrders().keySet());
      missionIds.addAll(target.missions().keySet());
    });
    Map<UUID, Set<UUID>> memberOf = new HashMap<>();
    memberships
        .listDirectMembershipOptionsByUser(holderIds)
        .forEach((holder, options) ->
            memberOf.put(
                holder,
                options.stream()
                    .map(OrgUnitMembershipOptionDto::orgUnitId)
                    .collect(Collectors.toUnmodifiableSet())));
    return new References(
        byId(users.findAllById(holderIds), User::getId),
        byId(pools.findAllById(poolIds), OrgUnit::getId),
        byId(
            materialIds.isEmpty() ? List.of() : materials.findAllById(materialIds),
            Material::getId),
        byId(itemIds.isEmpty() ? List.of() : items.findAllById(itemIds), GameItem::getId),
        byId(locations.findAllById(locationIds), Location::getId),
        Map.copyOf(memberOf),
        orderIds.isEmpty() ? Set.of() : Set.copyOf(orders.jobOrders(orderIds).keySet()),
        missionIds.isEmpty() ? Set.of() : Set.copyOf(missions.missions(missionIds).keySet()));
  }

  /** Indexes a batch of existing target references without retaining a mutable lookup map. */
  private static <T> Map<UUID, T> byId(Collection<T> values, Function<T, UUID> id) {
    return values.stream().collect(Collectors.toUnmodifiableMap(id, Function.identity()));
  }

  /** Applies the owning target existence and direct-pool-membership rules to batched references. */
  private static @Nullable Resolved resolve(
      PoolEntryState target, ParsedLot lot, References references) {
    Material material =
        lot.materialId() == null ? null : references.materials().get(lot.materialId());
    GameItem item = lot.gameItemId() == null ? null : references.items().get(lot.gameItemId());
    Location location = references.locations().get(lot.locationId());
    User holder = references.users().get(target.holder());
    OrgUnit pool = references.pools().get(target.poolUnitId());
    if ((material == null && item == null)
        || location == null
        || holder == null
        || pool == null
        || !references
            .memberships()
            .getOrDefault(target.holder(), Set.of())
            .contains(target.poolUnitId())
        || !references.orders().containsAll(target.jobOrders().keySet())
        || !references.missions().containsAll(target.missions().keySet())) {
      return null;
    }
    return new Resolved(material, item, location, lot.quality(), lot.stolen(), holder, pool);
  }

  /**
   * Reads the canonical material-or-item lot key without changing any target or catalogue state.
   */
  private static @Nullable ParsedLot parse(String lotKey) {
    List<String> parts = LOT_KEY_SEPARATOR.splitAsStream(lotKey).toList();
    if (parts.size() != 4
        || !parts.get(1).startsWith("l:")
        || !parts.get(2).startsWith("q:")
        || !parts.get(3).startsWith("s:")) {
      return null;
    }
    try {
      UUID catalogueId = UUID.fromString(parts.get(0).substring(2));
      UUID locationId = UUID.fromString(parts.get(1).substring(2));
      int quality = Integer.parseInt(parts.get(2).substring(2));
      boolean stolen = "1".equals(parts.get(3).substring(2));
      if (parts.get(0).startsWith("m:")) {
        return new ParsedLot(catalogueId, null, locationId, quality, stolen);
      }
      if (parts.get(0).startsWith("i:")) {
        return new ParsedLot(null, catalogueId, locationId, null, stolen);
      }
      return null;
    } catch (IllegalArgumentException _) {
      return null;
    }
  }
}
