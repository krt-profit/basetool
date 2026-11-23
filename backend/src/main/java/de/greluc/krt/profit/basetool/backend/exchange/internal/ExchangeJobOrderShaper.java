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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeItemRefDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgUnitDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead.AuecProgress;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead.ItemLine;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead.ItemProgress;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead.MaterialLine;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead.MaterialProgress;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeReadSet;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeReadSet.RelatingMember;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeReads;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRelation;
import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;

/**
 * Shapes the job orders of a member's set into the client read (REQ-OXCH-002): the
 * member-independent projection of {@link JobOrderExchangeReads} unchanged, plus the member's own
 * part — relations, unit names, assignee names and per-client refs (REQ-XCH-043), the own note and
 * the link. An assignee who withholds their name is left out (REQ-XCH-044). Every lookup runs once
 * per page.
 */
@Component
@RequiredArgsConstructor
class ExchangeJobOrderShaper {

  /** The detail page of an order in the Basetool; the order's id follows. */
  static final String LINK_PREFIX = "https://profit-base.online/orders/";

  private final JobOrderExchangeReads reads;
  private final JobOrderExchangeReadSet readSet;
  private final OrgUnitRepository orgUnits;
  private final ExchangeNameWithholdings nameWithholdings;
  private final ExchangeOrgStockRoster roster;
  private final MaterialRepository materials;
  private final QualityTierRepository qualityTiers;
  private final GameItemRepository gameItems;

  /**
   * Shapes the given orders of the member's set in the given order; an order that left the read set
   * since it was selected is left out.
   *
   * @param caller the acting member and client
   * @param member the member with their relating units
   * @param updatedAt each order's fingerprint instant, in the order of the page
   * @return the client reads of the orders
   */
  @NotNull
  @Unmodifiable
  List<ExchangeJobOrderDto> shape(
      @NotNull ExchangeCaller caller,
      @NotNull RelatingMember member,
      @NotNull Map<UUID, Instant> updatedAt) {
    if (updatedAt.isEmpty()) {
      return List.of();
    }
    Set<UUID> ids = updatedAt.keySet();
    Map<UUID, Set<JobOrderRelation>> relations = readSet.relations(member, ids);
    Map<UUID, JobOrderExchangeRead> projected = reads.project(relations.keySet());
    Context context =
        new Context(
            caller.member(),
            readSet.ownNotes(caller.member(), projected.keySet()),
            units(projected.values()),
            assignees(caller.clientId(), projected.values()),
            materialsOf(projected.values()),
            qualityTiersOf(projected.values()),
            gameItemsOf(projected.values()));
    return updatedAt.entrySet().stream()
        .filter(entry -> projected.containsKey(entry.getKey()))
        .map(entry ->
            toDto(
                Objects.requireNonNull(projected.get(entry.getKey())),
                entry.getValue(),
                Objects.requireNonNull(relations.get(entry.getKey())),
                context))
        .toList();
  }

  private static ExchangeJobOrderDto toDto(
      JobOrderExchangeRead read, Instant updatedAt, Set<JobOrderRelation> relations, Context c) {
    ExchangeJobOrderDto.ExchangeJobOrderDtoBuilder dto =
        ExchangeJobOrderDto.builder()
            .id(read.id())
            .number(read.number())
            .kind(read.kind())
            .status(read.status())
            .priority(read.priority())
            .requestingUnit(c.unit(read.requestingUnitId()))
            .responsibleUnit(c.unit(read.responsibleUnitId()))
            .supportingUnits(read.supportingUnitIds().stream().map(c::unit).toList())
            .createdAt(read.createdAt())
            .updatedAt(updatedAt)
            .relations(relations.stream().sorted().toList())
            .comment(read.comment())
            .contact(read.contact())
            .responsibilityInvalid(read.responsibilityInvalid())
            .link(LINK_PREFIX + read.id())
            .assignees(c.assigneesOf(read))
            .versions(
                new ExchangeJobOrderDto.Versions(
                    read.versions().lifecycle(),
                    read.versions().target(),
                    read.versions().comment()));
    switch (read.progress()) {
      case MaterialProgress material ->
          dto.materials(material.lines().stream().map(c::materialLine).toList());
      case ItemProgress item -> dto.items(item.lines().stream().map(c::itemLine).toList());
      case AuecProgress money ->
          dto.target(ExchangeAmounts.whole(money.target()))
              .netHandedOver(ExchangeAmounts.whole(money.netHandedOver()))
              .remaining(ExchangeAmounts.whole(money.remaining()))
              .overpaid(ExchangeAmounts.whole(money.overpaid()))
              .settlementOpen(money.settlementOpen());
    }
    return dto.build();
  }

  private Map<UUID, ExchangeOrgUnitDto> units(Collection<JobOrderExchangeRead> projected) {
    Set<UUID> ids = new HashSet<>();
    for (JobOrderExchangeRead read : projected) {
      ids.add(read.requestingUnitId());
      ids.add(read.responsibleUnitId());
      ids.addAll(read.supportingUnitIds());
    }
    return orgUnits.findAllById(ids).stream()
        .collect(
            Collectors.toMap(
                OrgUnit::getId,
                unit ->
                    new ExchangeOrgUnitDto(
                        unit.getId(), unit.getName(), unit.getShorthand(), unit.getKind())));
  }

  /**
   * Names the assignees of the page who do not withhold their name, in the roster's name order.
   *
   * @param clientId the client whose refs are derived
   * @param projected the orders of the page
   * @return each named assignee by member id, in name order
   */
  private Map<UUID, ExchangeOrgStockRoster.Holder> assignees(
      String clientId, Collection<JobOrderExchangeRead> projected) {
    Set<UUID> ids =
        projected.stream()
            .flatMap(read -> read.assigneeIds().stream())
            .collect(Collectors.toCollection(HashSet::new));
    if (ids.isEmpty()) {
      return Map.of();
    }
    ids.removeAll(nameWithholdings.withholding(ids));
    Map<UUID, ExchangeOrgStockRoster.Holder> named = new LinkedHashMap<>();
    roster.holders(clientId, ids).forEach(holder -> named.put(holder.userId(), holder));
    return named;
  }

  private Map<UUID, Material> materialsOf(Collection<JobOrderExchangeRead> projected) {
    return byId(
        materials.findAllById(
            materialLines(projected).map(MaterialLine::materialId).collect(Collectors.toSet())));
  }

  private Map<UUID, QualityTier> qualityTiersOf(Collection<JobOrderExchangeRead> projected) {
    return byId(
        qualityTiers.findAllById(
            materialLines(projected)
                .map(MaterialLine::qualityTierId)
                .collect(Collectors.toSet())));
  }

  private Map<UUID, GameItem> gameItemsOf(Collection<JobOrderExchangeRead> projected) {
    return byId(
        gameItems.findAllById(
            projected.stream()
                .flatMap(read ->
                    read.progress() instanceof ItemProgress item
                        ? item.lines().stream()
                        : Stream.<ItemLine>empty())
                .map(ItemLine::gameItemId)
                .collect(Collectors.toSet())));
  }

  private static Stream<MaterialLine> materialLines(Collection<JobOrderExchangeRead> projected) {
    return projected.stream()
        .flatMap(read ->
            read.progress() instanceof MaterialProgress material
                ? material.lines().stream()
                : Stream.empty());
  }

  private static <E extends AbstractEntity<UUID>> Map<UUID, E> byId(List<E> entities) {
    return entities.stream().collect(Collectors.toMap(AbstractEntity::getId, Function.identity()));
  }

  /**
   * The lookups of one page.
   *
   * @param member the acting member
   * @param ownNotes the member's own note by order
   * @param units the org units by id
   * @param named the named assignees by member id, in name order
   * @param materials the materials by id
   * @param qualityTiers the quality tiers by id
   * @param gameItems the game items by id
   */
  private record Context(
      UUID member,
      Map<UUID, String> ownNotes,
      Map<UUID, ExchangeOrgUnitDto> units,
      Map<UUID, ExchangeOrgStockRoster.Holder> named,
      Map<UUID, Material> materials,
      Map<UUID, QualityTier> qualityTiers,
      Map<UUID, GameItem> gameItems) {

    ExchangeOrgUnitDto unit(UUID id) {
      return Objects.requireNonNull(units.get(id));
    }

    List<ExchangeJobOrderDto.Assignee> assigneesOf(JobOrderExchangeRead read) {
      return named.values().stream()
          .filter(holder -> read.assigneeIds().contains(holder.userId()))
          .map(holder -> {
            boolean own = holder.userId().equals(member);
            return new ExchangeJobOrderDto.Assignee(
                holder.name(), holder.ref(), own, own ? ownNoteOf(read) : null);
          })
          .toList();
    }

    @Nullable
    private String ownNoteOf(JobOrderExchangeRead read) {
      return ownNotes.get(read.id());
    }

    ExchangeJobOrderDto.MaterialLine materialLine(MaterialLine line) {
      Material material = Objects.requireNonNull(materials.get(line.materialId()));
      String unit =
          material.getQuantityType() == QuantityType.PIECE
              ? QuantityType.PIECE.name()
              : QuantityType.SCU.name();
      return new ExchangeJobOrderDto.MaterialLine(
          new ExchangeItemRefDto(material.getId().toString(), ExchangeNames.cut(material.getName())),
          Objects.requireNonNull(qualityTiers.get(line.qualityTierId())).getMinQuality(),
          unit,
          ExchangeStockFeedService.amount(line.requested(), unit),
          ExchangeStockFeedService.amount(line.handedOver(), unit),
          ExchangeStockFeedService.amount(line.remaining(), unit),
          ExchangeStockFeedService.amount(line.open(), unit));
    }

    ExchangeJobOrderDto.ItemLine itemLine(ItemLine line) {
      GameItem item = Objects.requireNonNull(gameItems.get(line.gameItemId()));
      return new ExchangeJobOrderDto.ItemLine(
          line.lineId(),
          new ExchangeItemRefDto(item.getId().toString(), ExchangeNames.cut(item.getName())),
          line.ordered(),
          line.manufactured(),
          line.delivered(),
          line.open());
    }
  }
}
