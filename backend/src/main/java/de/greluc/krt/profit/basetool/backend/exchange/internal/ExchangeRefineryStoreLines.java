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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.OfferedLocations;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Outcome;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeCatalogKind;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeItemRef;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRefineryOrderChangeSet;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRefineryOrderChangeSet.Op;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRefineryOrderChangeSet.StoreLine;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeResolveRequest;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeResolveResponse;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeOrder;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeStoreLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;

/**
 * Resolves the store lines of a change set's pickups exactly and decides each by the exchange's
 * database rule, before anything is written (REQ-XCH-053, REQ-SEC-039): the material, the offered
 * location, the unit of the quantity, the receiver among the member's store choices, the unit among
 * the receiver's direct memberships and the member's Logistician units, and the job order. What
 * depends on the order, whether the material is one of its outputs, is decided when the op runs.
 */
@Component
@RequiredArgsConstructor
public class ExchangeRefineryStoreLines {

  /** The refusal of a receiver the member may not book onto, or that names nobody. */
  static final String RECEIVER_UNKNOWN = "RECEIVER_UNKNOWN";

  /** The refusal of a line's unit that is neither the receiver's nor one the member may book in. */
  static final String ORG_UNIT_NOT_ALLOWED = "ORG_UNIT_NOT_ALLOWED";

  /** The refusal of a job order that is unknown, inactive, invisible or does not need the line. */
  static final String JOB_ORDER_INVALID = "JOB_ORDER_INVALID";

  /** The refusal of a store line whose material is none of the order's outputs. */
  static final String MATERIAL_NOT_AN_OUTPUT = "MATERIAL_NOT_AN_OUTPUT";

  private final ExchangeResolveService resolveService;
  private final ExchangeLocationResolver locationResolver;
  private final MaterialRepository materialRepository;
  private final OrgUnitMembershipQueryService membershipQueryService;
  private final ExchangeStoreChoices storeChoices;

  /**
   * Resolves the store lines of every op, every material in one resolver call.
   *
   * @param caller the client and member
   * @param ops the ops
   * @return per op the resolved lines, empty for an op without lines, or the refusal of its first
   *     refused line
   * @throws BadRequestException when a line's amount rounds to zero
   */
  @NotNull
  @Unmodifiable
  List<Lines> resolve(@NotNull ExchangeCaller caller, @NotNull List<Op> ops) {
    if (ops.stream().allMatch(op -> linesOf(op).isEmpty())) {
      return ops.stream().map(_ -> Lines.NONE).toList();
    }
    Map<ExchangeItemRef, Outcome> unresolved = new HashMap<>();
    Map<ExchangeItemRef, Material> materials = materials(ops, unresolved);
    Context context =
        new Context(materials, unresolved, storeChoices.receivers(caller), new HashMap<>());
    return ops.stream().map(op -> lines(op, context)).toList();
  }

  /**
   * Refuses a pickup's store lines an order cannot take: a stored order, or a line whose material
   * is none of the order's outputs (REQ-XCH-053).
   *
   * @param order the order as the earlier ops left it
   * @param lines the resolved store lines
   * @return the refusal, or {@code null} when every line fits or there is none
   */
  static @Nullable Outcome refusalFor(
      @NotNull RefineryExchangeOrder order, @NotNull List<RefineryExchangeStoreLine> lines) {
    if (lines.isEmpty()) {
      return null;
    }
    if (order.storedAt() != null) {
      return Outcome.rejected(ExchangeRefineryOrderWriteService.ORDER_STORED);
    }
    Set<UUID> outputs = new HashSet<>();
    order.goods().forEach(good -> outputs.add(good.outputMaterialId()));
    return lines.stream().allMatch(line -> outputs.contains(line.materialId()))
        ? null
        : Outcome.rejected(MATERIAL_NOT_AN_OUTPUT);
  }

  /**
   * Resolves one op's lines.
   *
   * @param op the op
   * @param context what the change set resolved once
   * @return the lines, or the refusal of the first refused one
   */
  private @NotNull Lines lines(@NotNull Op op, @NotNull Context context) {
    List<RefineryExchangeStoreLine> resolved = new ArrayList<>();
    for (StoreLine line : linesOf(op)) {
      Outcome refusal = context.unresolved().get(line.material());
      if (refusal != null) {
        return new Lines(List.of(), refusal);
      }
      Material material = Objects.requireNonNull(context.materials().get(line.material()));
      String unit =
          material.getQuantityType() == QuantityType.PIECE
              ? QuantityType.PIECE.name()
              : QuantityType.SCU.name();
      if (!unit.equals(line.quantity().unit())) {
        return Lines.refused(ExchangeStockWriteService.UNIT_MISMATCH);
      }
      BigDecimal amount = ExchangeOps.round(line.quantity().amount(), unit);
      if (amount.signum() <= 0) {
        throw new BadRequestException("A store line's quantity rounds to zero");
      }
      Optional<Location> location = locationResolver.resolve(line.location());
      if (location.isEmpty() || !OfferedLocations.isOffered(location.get())) {
        return Lines.refused(ExchangeRefineryOrderReferences.LOCATION_UNKNOWN);
      }
      UUID receiver = context.receivers().resolve(line.receiver());
      if (receiver == null) {
        return Lines.refused(RECEIVER_UNKNOWN);
      }
      Set<UUID> memberships =
          context
              .memberships()
              .computeIfAbsent(receiver, membershipQueryService::findDirectMembershipOrgUnitIds);
      UUID orgUnit = unit(line, memberships, context.receivers().units());
      if (orgUnit == null && (line.orgUnit() != null || memberships.size() > 1)) {
        return Lines.refused(ORG_UNIT_NOT_ALLOWED);
      }
      UUID jobOrder = null;
      if (line.jobOrder() != null) {
        jobOrder = ExchangeRefineryOrderReferences.parse(line.jobOrder());
        if (jobOrder == null || !storeChoices.jobOrderAdmits(jobOrder, material.getId())) {
          return Lines.refused(JOB_ORDER_INVALID);
        }
      }
      resolved.add(
          new RefineryExchangeStoreLine(
              material.getId(),
              location.get().getId(),
              line.quality(),
              amount.doubleValue(),
              receiver,
              orgUnit,
              Boolean.TRUE.equals(line.personal()),
              jobOrder,
              StringNormalization.trimToNull(line.note())));
    }
    return new Lines(List.copyOf(resolved), null);
  }

  /**
   * Decides a line's unit: the named one when it is a direct membership of the receiver or a unit
   * the member is a Logistician of, else the receiver's only direct membership.
   *
   * @param line the line
   * @param memberships the receiver's direct memberships
   * @param logisticianUnits the units the member may book for others in
   * @return the unit, or {@code null} when the named one is not allowed, the receiver has no
   *     membership or several and the line names none
   */
  private static @Nullable UUID unit(
      @NotNull StoreLine line,
      @NotNull Set<UUID> memberships,
      @NotNull Set<UUID> logisticianUnits) {
    if (line.orgUnit() == null) {
      return memberships.size() == 1 ? memberships.iterator().next() : null;
    }
    UUID named = ExchangeRefineryOrderReferences.parse(line.orgUnit().id());
    return named != null
            && !memberships.isEmpty()
            && (memberships.contains(named) || logisticianUnits.contains(named))
        ? named
        : null;
  }

  /**
   * Resolves every line material the ops name in one resolver call.
   *
   * @param ops the ops
   * @param unresolved receives the outcome of every reference that names no single material
   * @return the material of every reference that resolved
   */
  private @NotNull Map<ExchangeItemRef, Material> materials(
      @NotNull List<Op> ops, @NotNull Map<ExchangeItemRef, Outcome> unresolved) {
    List<ExchangeItemRef> refs =
        List.copyOf(
            new LinkedHashSet<>(
                ops.stream()
                    .flatMap(op -> linesOf(op).stream())
                    .map(StoreLine::material)
                    .toList()));
    ExchangeResolveResponse response =
        resolveService.resolve(new ExchangeResolveRequest(ExchangeCatalogKind.MATERIAL, refs));
    Map<UUID, ExchangeItemRef> byId = new HashMap<>();
    for (ExchangeResolveResponse.Result result : response.results()) {
      ExchangeItemRef ref = refs.get(result.index());
      Outcome refusal =
          switch (result.status()) {
            case RESOLVED -> {
              byId.put(UUID.fromString(Objects.requireNonNull(result.ref()).bt()), ref);
              yield null;
            }
            case AMBIGUOUS ->
                new Outcome(
                    ExchangeOps.AMBIGUOUS, ExchangeRefineryOrderReferences.AMBIGUOUS_REASON);
            case UNMATCHED ->
                new Outcome(
                    ExchangeOps.UNMATCHED, ExchangeRefineryOrderReferences.UNMATCHED_REASON);
          };
      if (refusal != null) {
        unresolved.put(ref, refusal);
      }
    }
    Map<ExchangeItemRef, Material> resolved = new HashMap<>();
    for (Material material : materialRepository.findAllById(byId.keySet())) {
      resolved.put(byId.get(material.getId()), material);
    }
    for (ExchangeItemRef ref : byId.values()) {
      if (!resolved.containsKey(ref)) {
        unresolved.put(
            ref,
            new Outcome(ExchangeOps.UNMATCHED, ExchangeRefineryOrderReferences.UNMATCHED_REASON));
      }
    }
    return resolved;
  }

  /**
   * The store lines an op carries.
   *
   * @param op the op
   * @return a pickup's lines, empty for every other op and a pickup without lines
   */
  private static @NotNull List<StoreLine> linesOf(@NotNull Op op) {
    return ExchangeRefineryOrderChangeSet.PICKUP.equals(op.op()) && op.lines() != null
        ? op.lines()
        : List.of();
  }

  /**
   * What one change set resolves once for all its lines.
   *
   * @param materials the line materials by reference
   * @param unresolved the outcome of every material reference that did not resolve
   * @param receivers whom the member may book onto
   * @param memberships each receiver's direct memberships, read on first use
   */
  private record Context(
      @NotNull Map<ExchangeItemRef, Material> materials,
      @NotNull Map<ExchangeItemRef, Outcome> unresolved,
      @NotNull ExchangeStoreChoices.Receivers receivers,
      @NotNull Map<UUID, Set<UUID>> memberships) {}

  /**
   * The resolved store lines of one op, or the refusal of its first refused line.
   *
   * @param lines the lines in the op's order, empty when the op books nothing or one is refused
   * @param refusal the refusal, or {@code null}
   */
  record Lines(
      @NotNull @Unmodifiable List<RefineryExchangeStoreLine> lines, @Nullable Outcome refusal) {

    /** The lines of an op without store lines. */
    static final Lines NONE = new Lines(List.of(), null);

    /**
     * The lines of an op one line refuses.
     *
     * @param reason the refusal's reason
     * @return the lines
     */
    static @NotNull Lines refused(@NotNull String reason) {
      return new Lines(List.of(), Outcome.rejected(reason));
    }
  }
}
