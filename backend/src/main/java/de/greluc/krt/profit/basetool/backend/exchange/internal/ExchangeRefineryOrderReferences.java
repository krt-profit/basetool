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
import de.greluc.krt.profit.basetool.backend.catalogue.api.OfferedLocations;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RefiningMethod;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RefiningMethodRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Outcome;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeItemRef;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRefineryOrderChangeSet;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRefineryOrderChangeSet.Op;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeRules;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeStoreLine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;

/**
 * Resolves the references of refinery-order ops exactly, never fuzzily (REQ-XCH-052): a location by
 * the stock ops' rule and only a refinery, a refining method by code, then exact name, an input
 * material that may be refined, an owning unit among the member's direct memberships, and a
 * pickup's store lines through {@link ExchangeRefineryStoreLines}.
 */
@Component
@RequiredArgsConstructor
public class ExchangeRefineryOrderReferences {

  private final ExchangeRefineryMaterials materials;
  private final ExchangeLocationResolver locationResolver;
  private final RefiningMethodRepository refiningMethodRepository;
  private final OrgUnitMembershipQueryService membershipQueryService;
  private final ExchangeRefineryStoreLines storeLines;

  /**
   * Resolves the references of every op, every material an input or a store line names in one
   * resolver call and one read.
   *
   * @param caller the caller, whose memberships a create's unit must be among
   * @param ops the ops
   * @return the references of each op, in the ops' order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when a store line's
   *     quantity rounds to zero
   */
  public @NotNull @Unmodifiable List<Refs> resolve(
      @NotNull ExchangeCaller caller, @NotNull List<Op> ops) {
    Map<ExchangeItemRef, ExchangeRefineryMaterials.Resolved> inputs =
        materials.resolve(
            ops.stream()
                .flatMap(op ->
                    Stream.concat(
                        inputs(op).stream(), ExchangeRefineryStoreLines.materialsOf(op).stream()))
                .toList());
    Set<UUID> memberships =
        ops.stream().anyMatch(op -> op.kind() == ExchangeRefineryOrderChangeSet.Kind.CREATE)
            ? membershipQueryService.findDirectMembershipOrgUnitIds(caller.member())
            : Set.of();
    List<ExchangeRefineryStoreLines.Lines> lines = storeLines.resolve(caller, ops, inputs);
    List<Refs> refs = new ArrayList<>(ops.size());
    for (int i = 0; i < ops.size(); i++) {
      ExchangeRefineryStoreLines.Lines opLines = lines.get(i);
      Refs opRefs = refs(ops.get(i), inputs, memberships);
      refs.add(
          opRefs.refusal() != null || opLines.refusal() == null
              ? opRefs.withLines(opLines.lines())
              : Refs.refused(Objects.requireNonNull(opLines.refusal())));
    }
    return List.copyOf(refs);
  }

  /**
   * Resolves one op's references.
   *
   * @param op the op
   * @param resolved the input materials by reference
   * @param memberships the member's direct org-unit memberships, empty when no op creates
   * @return the references, or the refusal they lead to
   */
  private @NotNull Refs refs(
      @NotNull Op op,
      @NotNull Map<ExchangeItemRef, ExchangeRefineryMaterials.Resolved> resolved,
      @NotNull Set<UUID> memberships) {
    Location location = null;
    if (op.location() != null) {
      Optional<Location> found = locationResolver.resolve(op.location());
      if (found.isEmpty()) {
        return Refs.refused(Outcome.rejected(ExchangeReasons.LOCATION_UNKNOWN));
      }
      if (!RefineryExchangeRules.hostsRefinery(found.get())) {
        return Refs.refused(Outcome.rejected(ExchangeRefineryReasons.NOT_A_REFINERY));
      }
      location = found.get();
    }
    UUID method = null;
    if (op.method() != null) {
      MethodMatch match = method(op.method());
      if (match.refusal() != null) {
        return Refs.refused(match.refusal());
      }
      method = match.id();
    }
    List<UUID> inputs = new ArrayList<>();
    Map<UUID, UUID> outputs = new HashMap<>();
    for (ExchangeItemRef input : inputs(op)) {
      ExchangeRefineryMaterials.Resolved match = Objects.requireNonNull(resolved.get(input));
      Material material = match.material();
      if (material == null) {
        return Refs.refused(Objects.requireNonNull(match.refusal()));
      }
      if (!RefineryExchangeRules.refinable(material)) {
        return Refs.refused(Outcome.rejected(ExchangeRefineryReasons.MATERIAL_NOT_RAW));
      }
      inputs.add(material.getId());
      outputs.put(material.getId(), RefineryExchangeRules.output(material).getId());
    }
    UUID orgUnit = null;
    if (op.orgUnit() != null) {
      orgUnit = ExchangeIds.parse(op.orgUnit().id());
      if (orgUnit == null || !memberships.contains(orgUnit)) {
        return Refs.refused(Outcome.rejected(ExchangeReasons.NOT_A_MEMBER));
      }
    }
    return new Refs(
        null,
        location == null ? null : location.getId(),
        location == null ? null : location.getName(),
        location != null && OfferedLocations.isOffered(location),
        method,
        orgUnit,
        List.copyOf(inputs),
        Map.copyOf(outputs),
        List.of());
  }

  /**
   * Resolves a refining method: by its code first, then by its exact name.
   *
   * @param ref the client's reference
   * @return the method, or the outcome that ends the op
   */
  private @NotNull MethodMatch method(@NotNull ExchangeRefineryOrderChangeSet.MethodRef ref) {
    if (ref.code() != null) {
      List<RefiningMethod> byCode = refiningMethodRepository.findAllByCode(ref.code());
      if (byCode.size() > 1) {
        return new MethodMatch(null, new Outcome(ExchangeOps.AMBIGUOUS, ExchangeReasons.AMBIGUOUS));
      }
      if (byCode.size() == 1) {
        return new MethodMatch(byCode.getFirst().getId(), null);
      }
    }
    if (ref.name() != null) {
      Optional<RefiningMethod> byName = refiningMethodRepository.findByName(ref.name());
      if (byName.isPresent()) {
        return new MethodMatch(byName.get().getId(), null);
      }
    }
    return new MethodMatch(null, new Outcome(ExchangeOps.UNMATCHED, ExchangeReasons.UNMATCHED));
  }

  /**
   * The input materials an op names.
   *
   * @param op the op
   * @return a create's goods' inputs, a good-add's input, or a good-update's new input
   */
  private static @NotNull List<ExchangeItemRef> inputs(@NotNull Op op) {
    return switch (op.kind()) {
      case CREATE ->
          op.goods() == null
              ? List.of()
              : op.goods().stream().map(ExchangeRefineryOrderChangeSet.Good::input).toList();
      case GOOD_ADD -> List.of(Objects.requireNonNull(op.good()).input());
      case GOOD_UPDATE -> op.input() == null ? List.of() : List.of(op.input());
      case UPDATE, GOOD_REMOVE, CANCEL, DELETE, PICKUP -> List.of();
    };
  }

  /**
   * The references of one op, resolved exactly.
   *
   * @param refusal the refusal the references lead to, or {@code null}
   * @param location the location, or {@code null} when the op names none
   * @param locationName the location's name, or {@code null} when the op names none
   * @param locationOffered whether the location is one the web's picker offers
   * @param method the refining method, or {@code null} when the op names none
   * @param orgUnit the owning unit of a create, or {@code null}
   * @param inputs the input materials in the order the op names them
   * @param outputs the output material of each input
   * @param lines a pickup's store lines, resolved and decided; empty when it books nothing
   */
  record Refs(
      @Nullable Outcome refusal,
      @Nullable UUID location,
      @Nullable String locationName,
      boolean locationOffered,
      @Nullable UUID method,
      @Nullable UUID orgUnit,
      @NotNull @Unmodifiable List<UUID> inputs,
      @NotNull @Unmodifiable Map<UUID, UUID> outputs,
      @NotNull @Unmodifiable List<RefineryExchangeStoreLine> lines) {

    /**
     * The same references with a pickup's store lines.
     *
     * @param storeLines the lines
     * @return the references
     */
    @NotNull
    Refs withLines(@NotNull @Unmodifiable List<RefineryExchangeStoreLine> storeLines) {
      return new Refs(
          refusal,
          location,
          locationName,
          locationOffered,
          method,
          orgUnit,
          inputs,
          outputs,
          storeLines);
    }

    /**
     * The references of an op one of them refuses.
     *
     * @param refusal the refusal
     * @return the references
     */
    static @NotNull Refs refused(@NotNull Outcome refusal) {
      return new Refs(refusal, null, null, false, null, null, List.of(), Map.of(), List.of());
    }
  }

  /**
   * A resolved refining method, or the outcome that ends the op.
   *
   * @param id the method, or {@code null}
   * @param refusal the outcome, or {@code null}
   */
  private record MethodMatch(@Nullable UUID id, @Nullable Outcome refusal) {}
}
