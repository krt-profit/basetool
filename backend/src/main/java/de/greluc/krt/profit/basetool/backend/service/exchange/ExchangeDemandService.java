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

package de.greluc.krt.profit.basetool.backend.service.exchange;

import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderGameItemStockRow;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeDemandItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeDemandMaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeItemRefDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeOrgDemandDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeQuantityDto;
import de.greluc.krt.profit.basetool.backend.model.projection.BlueprintOwnerProduct;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.service.BlueprintVariantFamilyResolver;
import de.greluc.krt.profit.basetool.backend.service.JobOrderMaterialRequirementResolver;
import de.greluc.krt.profit.basetool.backend.service.JobOrderStockProjectionService;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.support.QuantityTypeRounding;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The anonymised open demand of the units a member belongs to, for exchange clients (REQ-XCH-018).
 *
 * <p>Only orders a unit of the member is responsible for count, never ones the member merely
 * oversees or administers. A material line is the outstanding requirement per material, quality
 * floor and source summed across those orders, as the Materialbedarf computes it; an item line is
 * what item orders still need per game item. Nothing names a person or an order. A member who fails
 * the web's job-order gate gets no demand at all, only the reason.
 */
@Service
@RequiredArgsConstructor
public class ExchangeDemandService {

  /** The source of a line from a material order. */
  static final String MATERIAL_ORDER = "material-order";

  /** The source of a line an item order resolves to. */
  static final String ITEM_ORDER = "item-order";

  /** The most raw ores the contract lists per material. */
  private static final int MAX_RAW_REFS = 20;

  /** The longest display name the published item reference carries. */
  private static final int MAX_NAME = 200;

  private static final List<JobOrderStatus> OPEN_STATUSES =
      List.of(JobOrderStatus.OPEN, JobOrderStatus.IN_PROGRESS);

  /** The bucket of orders without a responsible org unit, as the Materialbedarf groups them. */
  private static final UUID NO_UNIT = new UUID(0L, 0L);

  private final OrgUnitMembershipRepository membershipRepository;
  private final JobOrderRepository jobOrderRepository;
  private final JobOrderMaterialRequirementResolver requirementResolver;
  private final JobOrderStockProjectionService stockProjectionService;
  private final InventoryItemRepository inventoryRepository;
  private final MaterialRepository materialRepository;
  private final PersonalBlueprintRepository blueprintRepository;
  private final BlueprintVariantFamilyResolver familyResolver;

  /** The web's job-order gate, evaluated for the acting member. */
  private final OwnerScopeService ownerScopeService;

  private final Clock clock = Clock.systemUTC();

  /**
   * Computes the member's org demand, withheld unless the member passes the web's job-order gate.
   *
   * @param member the member the current request acts for
   * @return the demand; empty lists when the member belongs to no unit or no order is open, and
   *     empty lists with {@link ExchangeOrgDemandDto.Reason#NOT_PERMITTED} when {@link
   *     OwnerScopeService#canViewJobOrders()} refuses the member
   */
  @Transactional(readOnly = true)
  public @NotNull ExchangeOrgDemandDto demand(@NotNull UUID member) {
    if (!ownerScopeService.canViewJobOrders()) {
      return ExchangeOrgDemandDto.withheld(
          ExchangeOrgDemandDto.Reason.NOT_PERMITTED, clock.instant());
    }
    Set<UUID> units = membershipRepository.findOrgUnitIdsByUserId(member);
    List<JobOrder> orders =
        units.isEmpty()
            ? List.of()
            : jobOrderRepository.findOpenForExchangeDemand(OPEN_STATUSES, units);
    if (orders.isEmpty()) {
      return new ExchangeOrgDemandDto(List.of(), List.of(), clock.instant(), null);
    }
    return new ExchangeOrgDemandDto(
        materials(orders), items(member, orders), clock.instant(), null);
  }

  /**
   * Sums the outstanding material requirement of the orders per material, quality floor and source.
   *
   * @param orders the open orders
   * @return the material lines with a positive open quantity, SCU first then by name
   */
  private @NotNull List<ExchangeDemandMaterialDto> materials(@NotNull List<JobOrder> orders) {
    JobOrderStockProjectionService.OrderLinkedStockIndex stock =
        stockProjectionService.loadOrderLinkedStockIndex(
            orders.stream().map(JobOrder::getId).toList());
    Map<UnitBucket, double[]> buckets = new LinkedHashMap<>();
    Map<UUID, MaterialDto> materials = new HashMap<>();
    for (JobOrder order : orders) {
      String source = order.getType() == JobOrderType.ITEM ? ITEM_ORDER : MATERIAL_ORDER;
      UUID unit =
          order.getResponsibleOrgUnit() == null || order.getResponsibleOrgUnit().getId() == null
              ? NO_UNIT
              : order.getResponsibleOrgUnit().getId();
      List<JobOrderMaterialRequirementResolver.MaterialRequirement> requirements =
          requirementResolver.requirementsOf(order);
      double[] booked = stock.bookedFor(order.getId(), requirements);
      for (int i = 0; i < requirements.size(); i++) {
        JobOrderMaterialRequirementResolver.MaterialRequirement requirement = requirements.get(i);
        MaterialDto material = requirement.material();
        int floor = requirement.tier().minQuality();
        materials.putIfAbsent(material.id(), material);
        double[] totals =
            buckets.computeIfAbsent(
                new UnitBucket(unit, new MaterialLine(material.id(), floor, source)),
                key -> new double[2]);
        totals[0] += requirement.requiredAmount();
        totals[1] += booked[i];
      }
    }
    Map<MaterialLine, Double> open = new LinkedHashMap<>();
    buckets.forEach(
        (bucket, totals) -> {
          MaterialDto material = materials.get(bucket.line().materialId());
          double required = QuantityTypeRounding.roundForQuantityType(totals[0], material);
          double booked = QuantityTypeRounding.roundForQuantityType(totals[1], material);
          double gap =
              Math.max(0.0, QuantityTypeRounding.roundForQuantityType(required - booked, material));
          open.merge(bucket.line(), gap, Double::sum);
        });
    Map<UUID, List<ExchangeItemRefDto>> raws = rawRefs(materials.keySet());
    List<ExchangeDemandMaterialDto> lines = new ArrayList<>();
    open.forEach(
        (line, amount) -> {
          MaterialDto material = materials.get(line.materialId());
          String unit =
              QuantityType.PIECE.name().equals(material.quantityType())
                  ? QuantityType.PIECE.name()
                  : QuantityType.SCU.name();
          double rounded = QuantityTypeRounding.roundForQuantityType(amount, material);
          if (rounded <= 0) {
            return;
          }
          lines.add(
              new ExchangeDemandMaterialDto(
                  new ExchangeItemRefDto(material.id().toString(), cut(material.name())),
                  raws.getOrDefault(material.id(), List.of()),
                  line.minQuality(),
                  new ExchangeQuantityDto(ExchangeStockFeedService.amount(rounded, unit), unit),
                  line.source()));
        });
    lines.sort(
        Comparator.comparing((ExchangeDemandMaterialDto l) -> l.openQuantity().unit())
            .reversed()
            .thenComparing(l -> l.material().name(), String.CASE_INSENSITIVE_ORDER)
            .thenComparingInt(ExchangeDemandMaterialDto::minQuality)
            .thenComparing(ExchangeDemandMaterialDto::source));
    return lines;
  }

  /**
   * Lists the raw ores that refine into each of the given materials.
   *
   * @param materialIds the refined materials
   * @return the raw ores by refined material, at most {@value #MAX_RAW_REFS} each, by name
   */
  private @NotNull Map<UUID, List<ExchangeItemRefDto>> rawRefs(@NotNull Set<UUID> materialIds) {
    Map<UUID, List<ExchangeItemRefDto>> raws = new HashMap<>();
    List<Material> rawMaterials =
        new ArrayList<>(materialRepository.findAllByRefinedMaterialIdIn(materialIds));
    rawMaterials.sort(Comparator.comparing(Material::getName, String.CASE_INSENSITIVE_ORDER));
    for (Material raw : rawMaterials) {
      List<ExchangeItemRefDto> list =
          raws.computeIfAbsent(raw.getRefinedMaterial().getId(), id -> new ArrayList<>());
      if (list.size() < MAX_RAW_REFS) {
        list.add(new ExchangeItemRefDto(raw.getId().toString(), cut(raw.getName())));
      }
    }
    return raws;
  }

  /**
   * Sums what the item orders still need per game item: ordered minus delivered minus earmarked.
   *
   * @param member the member, for {@code craftableByMe}
   * @param orders the open orders
   * @return the item lines with a positive open quantity, by name
   */
  private @NotNull List<ExchangeDemandItemDto> items(
      @NotNull UUID member, @NotNull List<JobOrder> orders) {
    List<JobOrder> itemOrders =
        orders.stream().filter(o -> o.getType() == JobOrderType.ITEM).toList();
    if (itemOrders.isEmpty()) {
      return List.of();
    }
    Map<UUID, Map<UUID, Double>> earmarked = new HashMap<>();
    for (JobOrderGameItemStockRow row :
        inventoryRepository.findGameItemStockRowsByJobOrderIds(
            itemOrders.stream().map(JobOrder::getId).toList())) {
      if (row.jobOrderId() != null && row.gameItemId() != null) {
        earmarked
            .computeIfAbsent(row.jobOrderId(), id -> new HashMap<>())
            .merge(row.gameItemId(), row.amount() == null ? 0.0 : row.amount(), Double::sum);
      }
    }
    Set<String> exact = new HashSet<>();
    Set<String> families = new HashSet<>();
    for (BlueprintOwnerProduct owned :
        blueprintRepository.findOwnerProductByOwnerUserIdIn(Set.of(member))) {
      exact.add(familyResolver.matchKey(owned.productName(), false));
      families.add(familyResolver.matchKey(owned.productName(), true));
    }
    Map<UUID, ItemLine> lines = new LinkedHashMap<>();
    for (JobOrder order : itemOrders) {
      Map<UUID, Integer> orderOpen = new LinkedHashMap<>();
      for (JobOrderItem line : order.getItems()) {
        if (line.getGameItem() == null) {
          continue;
        }
        int ordered = line.getAmount() == null ? 0 : line.getAmount();
        int delivered = line.getDeliveredAmount() == null ? 0 : line.getDeliveredAmount();
        orderOpen.merge(line.getGameItem().getId(), ordered - delivered, Integer::sum);
        ItemLine item =
            lines.computeIfAbsent(
                line.getGameItem().getId(), id -> new ItemLine(line.getGameItem().getName()));
        String output = line.getBlueprint() == null ? null : line.getBlueprint().getOutputName();
        boolean variants =
            order.isCountBlueprintsWithVariants() && !familyResolver.isMagazine(output);
        String key = familyResolver.matchKey(output, variants);
        if (!key.isEmpty() && (variants ? families : exact).contains(key)) {
          item.craftable = true;
        }
      }
      Map<UUID, Double> marks = earmarked.getOrDefault(order.getId(), Map.of());
      orderOpen.forEach(
          (gameItemId, open) ->
              lines.get(gameItemId).open +=
                  Math.max(0, open - (int) Math.round(marks.getOrDefault(gameItemId, 0.0))));
    }
    List<ExchangeDemandItemDto> result = new ArrayList<>();
    lines.forEach(
        (gameItemId, line) -> {
          if (line.open > 0) {
            result.add(
                new ExchangeDemandItemDto(
                    new ExchangeItemRefDto(gameItemId.toString(), cut(line.name)),
                    new ExchangeQuantityDto(
                        ExchangeStockFeedService.amount(line.open, QuantityType.PIECE.name()),
                        QuantityType.PIECE.name()),
                    line.craftable));
          }
        });
    result.sort(Comparator.comparing(l -> l.item().name(), String.CASE_INSENSITIVE_ORDER));
    return result;
  }

  /**
   * Cuts a display name to the published limit.
   *
   * @param name the name
   * @return the name, at most {@value #MAX_NAME} characters
   */
  private static @NotNull String cut(@NotNull String name) {
    return name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
  }

  /**
   * The aggregation key of a material line.
   *
   * @param materialId the material
   * @param minQuality the quality floor
   * @param source the line's source
   */
  private record MaterialLine(@NotNull UUID materialId, int minQuality, @NotNull String source) {}

  /**
   * One material line within the org unit responsible for its orders, the bucket the Materialbedarf
   * nets required against booked stock in.
   *
   * @param unit the responsible org unit, or {@link #NO_UNIT}
   * @param line the material line
   */
  private record UnitBucket(@NotNull UUID unit, @NotNull MaterialLine line) {}

  /** The running total of one game item. */
  private static final class ItemLine {

    /** The game item's name. */
    private final String name;

    /** The pieces still needed. */
    private int open;

    /** Whether the member holds a blueprint that produces it. */
    private boolean craftable;

    /**
     * Starts a line.
     *
     * @param name the game item's name
     */
    private ItemLine(@NotNull String name) {
      this.name = name;
    }
  }
}
