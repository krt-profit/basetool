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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.blueprint.api.BlueprintFamilies;
import de.greluc.krt.profit.basetool.backend.blueprint.api.BlueprintOwnerProduct;
import de.greluc.krt.profit.basetool.backend.blueprint.api.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityTypeRounding;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeComponentDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeDemandItemDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeDemandMaterialDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeDemandOrderDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeDemandOrderItemDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeDemandOrderMaterialDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeDemandUnitDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeItemRefDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgDemandDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeQuantityDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.JobOrderGameItemStockRow;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockReads;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterialRequirementResolver;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStockProjectionService;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * what item orders still need per game item. Nothing names a person or an order, except that a
 * client holding {@code exchange.demand.details} also gets the open orders of the units the member
 * is a Logistician of, without their assignees (REQ-XCH-047). A member who fails the web's
 * job-order gate gets no demand at all, only the reason.
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
  private final StockReads stockReads;
  private final MaterialRepository materialRepository;
  private final PersonalBlueprintRepository blueprintRepository;
  private final BlueprintFamilies familyResolver;

  /** The web's job-order gate, evaluated for the acting member. */
  private final OwnerScopeService ownerScopeService;

  /** Names the units the member is a Logistician of, for the details. */
  private final ExchangeOrgUnitService orgUnitService;

  private final Clock clock = Clock.systemUTC();

  /**
   * Computes the member's org demand, withheld unless the member passes the web's job-order gate.
   *
   * @param member the member the current request acts for
   * @param details whether the client holds {@code exchange.demand.details}
   * @return the demand; empty lists when the member belongs to no unit or no order is open, and
   *     empty lists with {@link ExchangeOrgDemandDto.Reason#NOT_PERMITTED} when {@link
   *     OwnerScopeService#canViewJobOrders()} refuses the member; with {@code details}, the open
   *     orders of the member's Logistician units when the member is one in at least one unit
   */
  @Transactional(readOnly = true)
  public @NotNull ExchangeOrgDemandDto demand(@NotNull UUID member, boolean details) {
    if (!ownerScopeService.canViewJobOrders()) {
      return ExchangeOrgDemandDto.withheld(
          ExchangeOrgDemandDto.Reason.NOT_PERMITTED, clock.instant());
    }
    Set<UUID> units = membershipRepository.findOrgUnitIdsByUserId(member);
    Set<UUID> logisticianUnits = new HashSet<>();
    if (details && !units.isEmpty()) {
      logisticianUnits.addAll(orgUnitService.logisticianUnits(member));
      logisticianUnits.retainAll(units);
    }
    List<JobOrder> orders =
        units.isEmpty()
            ? List.of()
            : jobOrderRepository.findOpenForExchangeDemand(OPEN_STATUSES, units);
    if (orders.isEmpty()) {
      return new ExchangeOrgDemandDto(
          List.of(),
          List.of(),
          clock.instant(),
          null,
          logisticianUnits.isEmpty() ? null : List.of());
    }
    List<OrderNeed> needs = needs(orders);
    Map<UUID, Map<UUID, Double>> earmarked = earmarked(orders);
    return new ExchangeOrgDemandDto(
        materials(needs),
        items(member, orders, earmarked),
        clock.instant(),
        null,
        logisticianUnits.isEmpty() ? null : orders(needs, logisticianUnits, earmarked));
  }

  /**
   * Resolves the material requirement of every order and the stock linked to each requirement.
   *
   * @param orders the open orders
   * @return one need per order, in the orders' order
   */
  private @NotNull List<OrderNeed> needs(@NotNull List<JobOrder> orders) {
    JobOrderStockProjectionService.OrderLinkedStockIndex stock =
        stockProjectionService.loadOrderLinkedStockIndex(
            orders.stream().map(JobOrder::getId).toList());
    List<OrderNeed> needs = new ArrayList<>(orders.size());
    for (JobOrder order : orders) {
      List<JobOrderMaterialRequirementResolver.MaterialRequirement> requirements =
          requirementResolver.requirementsOf(order);
      double[] booked = stock.bookedFor(order.getId(), requirements);
      List<RequirementNeed> lines = new ArrayList<>(requirements.size());
      for (int i = 0; i < requirements.size(); i++) {
        lines.add(new RequirementNeed(requirements.get(i), booked == null ? 0.0 : booked[i]));
      }
      needs.add(new OrderNeed(order, lines));
    }
    return needs;
  }

  /**
   * Sums the outstanding material requirement of the orders per material, quality floor and source.
   *
   * @param needs the open orders with their requirements
   * @return the material lines with a positive open quantity, SCU first then by name
   */
  private @NotNull List<ExchangeDemandMaterialDto> materials(@NotNull List<OrderNeed> needs) {
    Map<UnitBucket, double[]> buckets = new LinkedHashMap<>();
    Map<UUID, MaterialDto> materials = new HashMap<>();
    for (OrderNeed need : needs) {
      JobOrder order = need.order();
      String source = order.getType() == JobOrderType.ITEM ? ITEM_ORDER : MATERIAL_ORDER;
      UUID unit =
          order.getResponsibleOrgUnit() == null || order.getResponsibleOrgUnit().getId() == null
              ? NO_UNIT
              : order.getResponsibleOrgUnit().getId();
      for (RequirementNeed line : need.lines()) {
        JobOrderMaterialRequirementResolver.MaterialRequirement requirement = line.requirement();
        MaterialDto material = requirement.material();
        int floor = requirement.tier().minQuality();
        materials.putIfAbsent(material.id(), material);
        double[] totals =
            buckets.computeIfAbsent(
                new UnitBucket(unit, new MaterialLine(material.id(), floor, source)),
                _ -> new double[2]);
        totals[0] += requirement.requiredAmount();
        totals[1] += line.booked();
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
          MaterialDto material = Objects.requireNonNull(materials.get(line.materialId()));
          double rounded = QuantityTypeRounding.roundForQuantityType(amount, material);
          if (rounded <= 0) {
            return;
          }
          lines.add(
              new ExchangeDemandMaterialDto(
                  ref(material),
                  raws.getOrDefault(material.id(), List.of()),
                  line.minQuality(),
                  quantity(rounded, material),
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
          raws.computeIfAbsent(
              Objects.requireNonNull(raw.getRefinedMaterial()).getId(), _ -> new ArrayList<>());
      if (list.size() < MAX_RAW_REFS) {
        list.add(new ExchangeItemRefDto(raw.getId().toString(), cut(raw.getName())));
      }
    }
    return raws;
  }

  /**
   * Sums the game items earmarked to each item order.
   *
   * @param orders the open orders
   * @return the earmarked pieces per item order and game item
   */
  private @NotNull Map<UUID, Map<UUID, Double>> earmarked(@NotNull List<JobOrder> orders) {
    List<UUID> itemOrders =
        orders.stream().filter(o -> o.getType() == JobOrderType.ITEM).map(JobOrder::getId).toList();
    Map<UUID, Map<UUID, Double>> earmarked = new HashMap<>();
    if (itemOrders.isEmpty()) {
      return earmarked;
    }
    for (JobOrderGameItemStockRow row : stockReads.gameItemStockOfOrders(itemOrders)) {
      if (row.jobOrderId() != null && row.gameItemId() != null) {
        earmarked
            .computeIfAbsent(row.jobOrderId(), _ -> new HashMap<>())
            .merge(row.gameItemId(), row.amount() == null ? 0.0 : row.amount(), Double::sum);
      }
    }
    return earmarked;
  }

  /**
   * Sums what the item orders still need per game item: ordered minus delivered minus earmarked.
   *
   * @param member the member, for {@code craftableByMe}
   * @param orders the open orders
   * @param earmarked the earmarked pieces per item order and game item
   * @return the item lines with a positive open quantity, by name
   */
  private @NotNull List<ExchangeDemandItemDto> items(
      @NotNull UUID member,
      @NotNull List<JobOrder> orders,
      @NotNull Map<UUID, Map<UUID, Double>> earmarked) {
    List<JobOrder> itemOrders =
        orders.stream().filter(o -> o.getType() == JobOrderType.ITEM).toList();
    if (itemOrders.isEmpty()) {
      return List.of();
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
      for (JobOrderItem line : order.getItems()) {
        if (line.getGameItem() == null) {
          continue;
        }
        ItemLine item =
            lines.computeIfAbsent(
                line.getGameItem().getId(), _ -> new ItemLine(line.getGameItem()));
        String output = line.getBlueprint() == null ? null : line.getBlueprint().getOutputName();
        boolean variants =
            order.isCountBlueprintsWithVariants() && !familyResolver.isMagazine(output);
        String key = familyResolver.matchKey(output, variants);
        if (!key.isEmpty() && (variants ? families : exact).contains(key)) {
          item.craftable = true;
        }
      }
      openPieces(order, earmarked)
          .forEach(
              (gameItemId, open) -> Objects.requireNonNull(lines.get(gameItemId)).open += open);
    }
    List<ExchangeDemandItemDto> result = new ArrayList<>();
    lines.forEach(
        (gameItemId, line) -> {
          if (line.open > 0) {
            result.add(
                new ExchangeDemandItemDto(
                    new ExchangeItemRefDto(gameItemId.toString(), cut(line.item.getName())),
                    ExchangeComponentDto.of(line.item),
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
   * Counts what one item order still needs per game item: ordered minus delivered minus earmarked,
   * clamped at zero.
   *
   * @param order the item order
   * @param earmarked the earmarked pieces per item order and game item
   * @return the open pieces per game item, in the order's line order, zero included
   */
  private static @NotNull Map<UUID, Integer> openPieces(
      @NotNull JobOrder order, @NotNull Map<UUID, Map<UUID, Double>> earmarked) {
    Map<UUID, Integer> ordered = new LinkedHashMap<>();
    for (JobOrderItem line : order.getItems()) {
      if (line.getGameItem() == null) {
        continue;
      }
      int amount = line.getAmount() == null ? 0 : line.getAmount();
      int delivered = line.getDeliveredAmount() == null ? 0 : line.getDeliveredAmount();
      ordered.merge(line.getGameItem().getId(), amount - delivered, Integer::sum);
    }
    Map<UUID, Double> marks = earmarked.getOrDefault(order.getId(), Map.of());
    ordered.replaceAll(
        (gameItemId, open) ->
            Math.max(0, open - (int) Math.round(marks.getOrDefault(gameItemId, 0.0))));
    return ordered;
  }

  /**
   * Lists the open orders of the member's Logistician units with their open lines, without
   * assignees (REQ-XCH-047).
   *
   * @param needs the open orders of the member's units with their requirements
   * @param detailed the units the member is a Logistician of
   * @param earmarked the earmarked pieces per item order and game item
   * @return the orders whose responsible unit is detailed, in the orders' order
   */
  private @NotNull List<ExchangeDemandOrderDto> orders(
      @NotNull List<OrderNeed> needs,
      @NotNull Set<UUID> detailed,
      @NotNull Map<UUID, Map<UUID, Double>> earmarked) {
    List<ExchangeDemandOrderDto> result = new ArrayList<>();
    for (OrderNeed need : needs) {
      JobOrder order = need.order();
      OrgUnit responsible = order.getResponsibleOrgUnit();
      if (responsible == null || !detailed.contains(responsible.getId())) {
        continue;
      }
      result.add(
          new ExchangeDemandOrderDto(
              order.getId(),
              order.getDisplayId(),
              order.getType().name(),
              order.getStatus().name(),
              order.getPriority(),
              unit(responsible),
              order.getRequestingOrgUnit() == null ? null : unit(order.getRequestingOrgUnit()),
              order.getHandle(),
              order.getComment(),
              orderMaterials(need),
              orderItems(order, earmarked)));
    }
    return result;
  }

  /**
   * Computes one order's open material lines: per material and quality floor, its requirement less
   * the stock linked to it, each rounded to the material's precision and clamped at zero.
   *
   * @param need the order with its requirements
   * @return the lines with a positive open quantity, SCU first then by name and floor
   */
  private static @NotNull List<ExchangeDemandOrderMaterialDto> orderMaterials(
      @NotNull OrderNeed need) {
    Map<MaterialLine, double[]> totals = new LinkedHashMap<>();
    Map<UUID, MaterialDto> materials = new HashMap<>();
    for (RequirementNeed requirementNeed : need.lines()) {
      JobOrderMaterialRequirementResolver.MaterialRequirement requirement =
          requirementNeed.requirement();
      MaterialDto material = requirement.material();
      materials.putIfAbsent(material.id(), material);
      double[] sum =
          totals.computeIfAbsent(
              new MaterialLine(
                  material.id(),
                  requirement.tier().minQuality(),
                  need.order().getType() == JobOrderType.ITEM ? ITEM_ORDER : MATERIAL_ORDER),
              _ -> new double[2]);
      sum[0] += requirement.requiredAmount();
      sum[1] += requirementNeed.booked();
    }
    List<ExchangeDemandOrderMaterialDto> lines = new ArrayList<>();
    totals.forEach(
        (line, sum) -> {
          MaterialDto material = Objects.requireNonNull(materials.get(line.materialId()));
          double required = QuantityTypeRounding.roundForQuantityType(sum[0], material);
          double booked = QuantityTypeRounding.roundForQuantityType(sum[1], material);
          double gap =
              Math.max(0.0, QuantityTypeRounding.roundForQuantityType(required - booked, material));
          if (gap > 0) {
            lines.add(
                new ExchangeDemandOrderMaterialDto(
                    ref(material), line.minQuality(), quantity(gap, material)));
          }
        });
    lines.sort(
        Comparator.comparing((ExchangeDemandOrderMaterialDto l) -> l.openQuantity().unit())
            .reversed()
            .thenComparing(l -> l.material().name(), String.CASE_INSENSITIVE_ORDER)
            .thenComparingInt(ExchangeDemandOrderMaterialDto::minQuality));
    return lines;
  }

  /**
   * Computes one item order's open item lines.
   *
   * @param order the order
   * @param earmarked the earmarked pieces per item order and game item
   * @return the lines with a positive open quantity, by name; empty for a material order
   */
  private static @NotNull List<ExchangeDemandOrderItemDto> orderItems(
      @NotNull JobOrder order, @NotNull Map<UUID, Map<UUID, Double>> earmarked) {
    if (order.getType() != JobOrderType.ITEM) {
      return List.of();
    }
    Map<UUID, GameItem> gameItems = new HashMap<>();
    for (JobOrderItem line : order.getItems()) {
      if (line.getGameItem() != null) {
        gameItems.putIfAbsent(line.getGameItem().getId(), line.getGameItem());
      }
    }
    List<ExchangeDemandOrderItemDto> lines = new ArrayList<>();
    openPieces(order, earmarked)
        .forEach(
            (gameItemId, open) -> {
              if (open > 0) {
                GameItem gameItem = Objects.requireNonNull(gameItems.get(gameItemId));
                lines.add(
                    new ExchangeDemandOrderItemDto(
                        new ExchangeItemRefDto(gameItemId.toString(), cut(gameItem.getName())),
                        ExchangeComponentDto.of(gameItem),
                        new ExchangeQuantityDto(
                            ExchangeStockFeedService.amount(open, QuantityType.PIECE.name()),
                            QuantityType.PIECE.name())));
              }
            });
    lines.sort(Comparator.comparing(l -> l.item().name(), String.CASE_INSENSITIVE_ORDER));
    return lines;
  }

  /**
   * Names an org unit for the details.
   *
   * @param unit the unit
   * @return its id and display name
   */
  private static @NotNull ExchangeDemandUnitDto unit(@NotNull OrgUnit unit) {
    return new ExchangeDemandUnitDto(unit.getId(), unit.getName());
  }

  /**
   * References a material as the published item reference.
   *
   * @param material the material
   * @return its id as {@code bt} and its name cut to the limit
   */
  private static @NotNull ExchangeItemRefDto ref(@NotNull MaterialDto material) {
    return new ExchangeItemRefDto(material.id().toString(), cut(material.name()));
  }

  /**
   * Expresses a rounded amount of a material in its published unit.
   *
   * @param amount the amount, already rounded to the material's precision
   * @param material the material
   * @return the amount with {@code PIECE} for piece materials and {@code SCU} otherwise
   */
  private static @NotNull ExchangeQuantityDto quantity(
      double amount, @NotNull MaterialDto material) {
    String unit =
        QuantityType.PIECE.name().equals(material.quantityType())
            ? QuantityType.PIECE.name()
            : QuantityType.SCU.name();
    return new ExchangeQuantityDto(ExchangeStockFeedService.amount(amount, unit), unit);
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
   * One open order with its resolved material requirement and the stock linked to each requirement.
   *
   * @param order the order
   * @param lines its material requirements, each with the stock linked to it
   */
  private record OrderNeed(@NotNull JobOrder order, @NotNull List<RequirementNeed> lines) {}

  /**
   * One material requirement of an order with the stock linked to it.
   *
   * @param requirement the requirement
   * @param booked the stock booked to the order for it
   */
  private record RequirementNeed(
      @NotNull JobOrderMaterialRequirementResolver.MaterialRequirement requirement,
      double booked) {}

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

    /** The game item. */
    private final GameItem item;

    /** The pieces still needed. */
    private int open;

    /** Whether the member holds a blueprint that produces it. */
    private boolean craftable;

    /**
     * Starts a line.
     *
     * @param item the game item
     */
    private ItemLine(@NotNull GameItem item) {
      this.item = item;
    }
  }
}
