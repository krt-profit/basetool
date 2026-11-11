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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import de.greluc.krt.profit.basetool.backend.blueprint.api.CraftabilitySlice;
import de.greluc.krt.profit.basetool.backend.blueprint.api.CraftabilityYieldSource;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RefineryYield;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RefineryYieldRepository;
import de.greluc.krt.profit.basetool.backend.kernel.LikePatterns;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeRules;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderCountsDto;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryYieldBonusDto;
import de.greluc.krt.profit.basetool.backend.scope.api.AccessGateService;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopePredicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the refinery orders: the scoped lists (REQ-REFINERY-019), the single-order lookup,
 * the UEX yield bonus per location and the pending yield the craftability calculation counts.
 *
 * <p>Read-only transactional; writes live in {@link RefineryOrderService} and {@link
 * RefineryOrderStoreService}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefineryOrderQueryService implements CraftabilityYieldSource {

  private final RefineryOrderRepository refineryOrderRepository;
  private final LocationRepository locationRepository;
  private final RefineryYieldRepository refineryYieldRepository;
  private final RequestScopeResolver requestScopeResolver;
  private final AccessGateService accessGateService;

  /**
   * Counts the list's four segments without loading orders or goods.
   *
   * @param userId the caller's identifier
   * @param onlyMine whether only the caller's own orders are counted
   * @return the scoped segment counts
   */
  public RefineryOrderCountsDto countSegments(@NotNull UUID userId, boolean onlyMine) {
    ScopePredicate scope =
        onlyMine ? null : requestScopeResolver.currentScopePredicateGranting(Roles.LOGISTICIAN);
    return refineryOrderRepository.countSegments(
        onlyMine,
        userId,
        List.of(RefineryOrderStatus.OPEN, RefineryOrderStatus.IN_PROGRESS),
        RefineryOrderStatus.COMPLETED,
        Instant.now(),
        scope != null && scope.adminAllScope(),
        scope == null ? null : scope.activeOrgUnitId(),
        scope == null ? Set.of() : scope.memberOrgUnitIds());
  }

  /**
   * Owner-scoped paged list under the list filters (REQ-REFINERY-019).
   *
   * @param userId owner id
   * @param statuses optional status filter; {@code null} or empty means every status
   * @param readyOnly {@code true} to keep only orders whose end is unknown or has passed
   * @param query optional free-text search; {@code null} or blank means no search
   * @param pageable page request
   * @return one page of the user's matching orders
   */
  public Page<RefineryOrder> getMyRefineryOrders(
      @NotNull UUID userId,
      @Nullable List<RefineryOrderStatus> statuses,
      boolean readyOnly,
      @Nullable String query,
      @NotNull Pageable pageable) {
    return refineryOrderRepository.findOwnedFiltered(
        userId, statusesOrAll(statuses), readyOnly, Instant.now(), searchPattern(query), pageable);
  }

  /**
   * The statuses a list filters on: the given ones, or every status when none is given.
   *
   * @param statuses the requested statuses, or {@code null}
   * @return a non-empty status set
   */
  @NotNull
  private static Set<RefineryOrderStatus> statusesOrAll(
      @Nullable List<RefineryOrderStatus> statuses) {
    if (statuses == null || statuses.isEmpty()) {
      return EnumSet.allOf(RefineryOrderStatus.class);
    }
    return EnumSet.copyOf(statuses);
  }

  /**
   * The LIKE pattern of a list search: the stripped, lower-cased text with its wildcards escaped,
   * wrapped in {@code %…%}.
   *
   * @param query the search text, or {@code null}
   * @return the pattern, or {@code null} for a {@code null} or blank query
   */
  @Nullable
  static String searchPattern(@Nullable String query) {
    String trimmed = StringNormalization.trimToNull(query);
    return trimmed == null ? null : LikePatterns.contains(trimmed.toLowerCase(Locale.ROOT));
  }

  /**
   * Lists a target user's refinery orders for the cross-user oversight endpoint, limited to the
   * units in which the caller is Logistician.
   *
   * <p>The {@code @PreAuthorize} gate only checks a shared org unit; the per-row scope filter here
   * hides orders stamped to units in which the caller is no Logistician.
   *
   * @param targetUserId the user whose orders to list; never {@code null}
   * @param pageable page request
   * @return the in-scope page of the target user's orders
   */
  public Page<RefineryOrder> getUserRefineryOrdersScoped(
      @NotNull UUID targetUserId, @NotNull Pageable pageable) {
    ScopePredicate scope = accessGateService.currentLogisticianScope();
    return refineryOrderRepository.findByOwnerIdScoped(
        targetUserId,
        scope.adminAllScope(),
        scope.activeOrgUnitId(),
        scope.memberOrgUnitIds(),
        pageable);
  }

  /**
   * Sums the yield of the user's {@code OPEN} and {@code IN_PROGRESS} refinery orders into one SCU
   * total per (output material, quality), for the craftability calculation.
   *
   * @param userId the owning user; never {@code null}
   * @return one slice per (output material, quality), with the summed SCU yield; never {@code null}
   */
  @Override
  @NotNull
  public List<CraftabilitySlice> pendingYieldSlices(@NotNull UUID userId) {
    List<RefineryOrder> orders =
        refineryOrderRepository.findOwnedWithGoodsByStatusIn(
            userId, List.of(RefineryOrderStatus.OPEN, RefineryOrderStatus.IN_PROGRESS));
    Map<UUID, Map<Integer, Double>> pooled = new HashMap<>();
    for (RefineryOrder order : orders) {
      if (order.getGoods() == null) {
        continue;
      }
      for (RefineryGood good : order.getGoods()) {
        Material material = good.getOutputMaterial();
        if (material == null
            || material.getId() == null
            || good.getOutputQuantity() == null
            || good.getQuality() == null) {
          continue;
        }
        double scu = toScu(good.getOutputQuantity(), material.getQuantityType());
        if (scu <= 0.0d) {
          continue;
        }
        pooled
            .computeIfAbsent(material.getId(), _ -> new HashMap<>())
            .merge(good.getQuality(), scu, Double::sum);
      }
    }
    List<CraftabilitySlice> slices = new ArrayList<>();
    pooled.forEach((materialId, byQuality) ->
        byQuality.forEach(
            (quality, scu) -> slices.add(new CraftabilitySlice(materialId, quality, scu))));
    return slices;
  }

  /**
   * Converts a refinery good's output quantity from units to SCU (100 units per SCU for SCU
   * commodities; others unchanged).
   *
   * @param outputQuantityUnits the good's output quantity in units
   * @param quantityType the output material's quantity type (may be {@code null})
   * @return the equivalent amount in SCU
   */
  private static double toScu(int outputQuantityUnits, QuantityType quantityType) {
    if (quantityType == QuantityType.SCU) {
      return outputQuantityUnits / RefineryExchangeRules.UNITS_PER_SCU;
    }
    return outputQuantityUnits;
  }

  /**
   * Lists the refinery orders linked to a mission that the caller may see on the mission: their
   * own, plus every order of the units in which they are Logistician (REQ-SEC-005).
   *
   * @param missionId mission id
   * @param userId the caller's user id
   * @return the caller's own orders and the orders of their Logistician units, each once
   */
  public List<RefineryOrder> getMissionRefineryOrdersForCaller(
      @NotNull UUID missionId, @NotNull UUID userId) {
    Map<UUID, RefineryOrder> byId = new LinkedHashMap<>();
    ScopePredicate scope = accessGateService.currentLogisticianScope();
    if (scope.adminAllScope()
        || scope.activeOrgUnitId() != null
        || !scope.memberOrgUnitIds().isEmpty()) {
      refineryOrderRepository
          .findByMissionIdScoped(
              missionId, scope.adminAllScope(), scope.activeOrgUnitId(), scope.memberOrgUnitIds())
          .forEach(o -> byId.put(o.getId(), o));
    }
    refineryOrderRepository
        .findByMissionIdAndOwnerId(missionId, userId)
        .forEach(o -> byId.putIfAbsent(o.getId(), o));
    return List.copyOf(byId.values());
  }

  /**
   * Paged list of the in-scope orders under the list filters (REQ-REFINERY-019).
   *
   * @param statuses optional status filter; {@code null} or empty means every status
   * @param readyOnly {@code true} to keep only orders whose end is unknown or has passed
   * @param query optional free-text search; {@code null} or blank means no search
   * @param pageable page request
   * @return one page of the matching in-scope orders
   */
  public Page<RefineryOrder> getAllRefineryOrders(
      @Nullable List<RefineryOrderStatus> statuses,
      boolean readyOnly,
      @Nullable String query,
      @NotNull Pageable pageable) {
    ScopePredicate scope = requestScopeResolver.currentScopePredicateGranting(Roles.LOGISTICIAN);
    return refineryOrderRepository.findFilteredScoped(
        statusesOrAll(statuses),
        readyOnly,
        Instant.now(),
        searchPattern(query),
        scope.adminAllScope(),
        scope.activeOrgUnitId(),
        scope.memberOrgUnitIds(),
        pageable);
  }

  /**
   * Returns the order.
   *
   * @param id refinery order primary key
   * @return the order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when no match
   */
  public RefineryOrder getRefineryOrder(@NotNull UUID id) {
    return RefineryOrders.require(refineryOrderRepository, id);
  }

  /**
   * Returns the UEX yield bonus per material for the refinery at {@code location}, with its 7- and
   * 30-day averages (REQ-REFINERY-026); an empty map means no known yield data.
   *
   * @param location the order's chosen location, may be {@code null}
   * @return map keyed by material UUID, never {@code null}
   */
  @NotNull
  public Map<UUID, RefineryYieldBonusDto> getYieldBonusByMaterialForLocation(
      @Nullable Location location) {
    if (location == null) {
      return Map.of();
    }
    String cityName = location.getCity() != null ? location.getCity().getName() : null;
    String stationName =
        location.getSpaceStation() != null ? location.getSpaceStation().getName() : null;
    if (cityName == null && stationName == null) {
      return Map.of();
    }
    Map<UUID, RefineryYieldBonusDto> result = new HashMap<>();
    for (RefineryYield yield : refineryYieldRepository.findAllForLocation(cityName, stationName)) {
      if (yield.getMaterial() == null || yield.getMaterial().getId() == null) {
        continue;
      }
      result.putIfAbsent(
          yield.getMaterial().getId(),
          new RefineryYieldBonusDto(
              yield.getYieldBonus(), yield.getYieldBonusWeek(), yield.getYieldBonusMonth()));
    }
    return result;
  }

  /**
   * Resolves {@code locationId} and delegates to {@link
   * #getYieldBonusByMaterialForLocation(Location)}; a {@code null} or unknown id yields an empty
   * map.
   *
   * @param locationId target location id; may be {@code null}
   * @return per-material yield bonus map for the location, never {@code null}
   */
  public Map<UUID, RefineryYieldBonusDto> getYieldBonusByMaterialForLocationId(UUID locationId) {
    if (locationId == null) {
      return Map.of();
    }
    return locationRepository
        .findById(locationId)
        .map(this::getYieldBonusByMaterialForLocation)
        .orElseGet(Map::of);
  }
}
