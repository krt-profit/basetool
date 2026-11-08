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
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.OfferedLocations;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RefiningMethod;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RefiningMethodRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Outcome;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeCatalogKind;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeItemRef;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRefineryOrderChangeSet;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRefineryOrderChangeSet.Op;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeResolveRequest;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeResolveResponse;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeRules;
import java.util.ArrayList;
import java.util.HashMap;
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
 * Resolves the references of refinery-order ops exactly, never fuzzily (REQ-XCH-052): a location by
 * the stock ops' rule and only a refinery, a refining method by code, then exact name, an input
 * material that may be refined, and an owning unit among the member's direct memberships.
 */
@Component
@RequiredArgsConstructor
public class ExchangeRefineryOrderReferences {

  /** The refusal of a place without a Lager location. */
  static final String LOCATION_UNKNOWN = "LOCATION_UNKNOWN";

  /** The refusal of a location the web's refinery picker does not offer. */
  static final String NOT_A_REFINERY = "NOT_A_REFINERY";

  /** The refusal of an input material that is neither raw nor manual-raw. */
  static final String MATERIAL_NOT_RAW = "MATERIAL_NOT_RAW";

  /** The refusal of an owning unit that is not one of the member's direct memberships. */
  static final String NOT_A_MEMBER = "NOT_A_MEMBER";

  /** The reason of a reference that names nothing. */
  static final String UNMATCHED_REASON = "UNMATCHED";

  /** The reason of a reference that names several entries. */
  static final String AMBIGUOUS_REASON = "AMBIGUOUS";

  private final ExchangeResolveService resolveService;
  private final ExchangeLocationResolver locationResolver;
  private final RefiningMethodRepository refiningMethodRepository;
  private final MaterialRepository materialRepository;
  private final OrgUnitMembershipQueryService membershipQueryService;

  /**
   * Resolves the references of every op, every input material in one resolver call.
   *
   * @param caller the caller, whose memberships a create's unit must be among
   * @param ops the ops
   * @return the references of each op, in the ops' order
   */
  public @NotNull @Unmodifiable List<Refs> resolve(
      @NotNull ExchangeCaller caller, @NotNull List<Op> ops) {
    Map<ExchangeItemRef, Outcome> unresolved = new HashMap<>();
    Map<ExchangeItemRef, UUID> materials = materials(ops, unresolved);
    Set<UUID> memberships =
        ops.stream().anyMatch(op -> ExchangeRefineryOrderChangeSet.CREATE.equals(op.op()))
            ? membershipQueryService.findDirectMembershipOrgUnitIds(caller.member())
            : Set.of();
    return ops.stream().map(op -> refs(op, materials, unresolved, memberships)).toList();
  }

  /**
   * Resolves one op's references.
   *
   * @param op the op
   * @param materials the resolved input materials by reference
   * @param unresolved the outcome of every reference that did not resolve to a refinable material
   * @param memberships the member's direct org-unit memberships, empty when no op creates
   * @return the references, or the refusal they lead to
   */
  private @NotNull Refs refs(
      @NotNull Op op,
      @NotNull Map<ExchangeItemRef, UUID> materials,
      @NotNull Map<ExchangeItemRef, Outcome> unresolved,
      @NotNull Set<UUID> memberships) {
    UUID location = null;
    boolean offered = false;
    if (op.location() != null) {
      Optional<Location> found = locationResolver.resolve(op.location());
      if (found.isEmpty()) {
        return Refs.refused(Outcome.rejected(LOCATION_UNKNOWN));
      }
      if (!RefineryExchangeRules.hostsRefinery(found.get())) {
        return Refs.refused(Outcome.rejected(NOT_A_REFINERY));
      }
      location = found.get().getId();
      offered = OfferedLocations.isOffered(found.get());
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
    for (ExchangeItemRef input : inputs(op)) {
      Outcome refusal = unresolved.get(input);
      if (refusal != null) {
        return Refs.refused(refusal);
      }
      inputs.add(Objects.requireNonNull(materials.get(input)));
    }
    UUID orgUnit = null;
    if (op.orgUnit() != null) {
      orgUnit = parse(op.orgUnit().id());
      if (orgUnit == null || !memberships.contains(orgUnit)) {
        return Refs.refused(Outcome.rejected(NOT_A_MEMBER));
      }
    }
    return new Refs(null, location, offered, method, orgUnit, List.copyOf(inputs));
  }

  /**
   * Resolves every input material the ops name in one resolver call.
   *
   * @param ops the ops
   * @param unresolved receives the outcome of every reference that names no refinable material
   * @return the material of every reference that resolved to a refinable one
   */
  private @NotNull Map<ExchangeItemRef, UUID> materials(
      @NotNull List<Op> ops, @NotNull Map<ExchangeItemRef, Outcome> unresolved) {
    List<ExchangeItemRef> refs =
        List.copyOf(new LinkedHashSet<>(ops.stream().flatMap(op -> inputs(op).stream()).toList()));
    Map<ExchangeItemRef, UUID> resolved = new HashMap<>();
    if (refs.isEmpty()) {
      return resolved;
    }
    ExchangeResolveResponse response =
        resolveService.resolve(new ExchangeResolveRequest(ExchangeCatalogKind.MATERIAL, refs));
    for (ExchangeResolveResponse.Result result : response.results()) {
      ExchangeItemRef ref = refs.get(result.index());
      Outcome refusal =
          switch (result.status()) {
            case RESOLVED -> {
              UUID id = UUID.fromString(Objects.requireNonNull(result.ref()).bt());
              boolean refinable =
                  materialRepository
                      .findById(id)
                      .filter(RefineryExchangeRules::refinable)
                      .isPresent();
              if (refinable) {
                resolved.put(ref, id);
              }
              yield refinable ? null : Outcome.rejected(MATERIAL_NOT_RAW);
            }
            case AMBIGUOUS -> new Outcome(ExchangeOps.AMBIGUOUS, AMBIGUOUS_REASON);
            case UNMATCHED -> new Outcome(ExchangeOps.UNMATCHED, UNMATCHED_REASON);
          };
      if (refusal != null) {
        unresolved.put(ref, refusal);
      }
    }
    return resolved;
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
        return new MethodMatch(null, new Outcome(ExchangeOps.AMBIGUOUS, AMBIGUOUS_REASON));
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
    return new MethodMatch(null, new Outcome(ExchangeOps.UNMATCHED, UNMATCHED_REASON));
  }

  /**
   * The input materials an op names.
   *
   * @param op the op
   * @return a create's goods' inputs, a good-add's input, or a good-update's new input
   */
  private static @NotNull List<ExchangeItemRef> inputs(@NotNull Op op) {
    return switch (op.op()) {
      case ExchangeRefineryOrderChangeSet.CREATE ->
          op.goods() == null
              ? List.of()
              : op.goods().stream().map(ExchangeRefineryOrderChangeSet.Good::input).toList();
      case ExchangeRefineryOrderChangeSet.GOOD_ADD ->
          List.of(Objects.requireNonNull(op.good()).input());
      case ExchangeRefineryOrderChangeSet.GOOD_UPDATE ->
          op.input() == null ? List.of() : List.of(op.input());
      default -> List.of();
    };
  }

  /**
   * Reads a client's id.
   *
   * @param raw the id as sent
   * @return the id, or {@code null} when it is no Basetool id
   */
  static @Nullable UUID parse(@NotNull String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException _) {
      return null;
    }
  }

  /**
   * The references of one op, resolved exactly.
   *
   * @param refusal the refusal the references lead to, or {@code null}
   * @param location the location, or {@code null} when the op names none
   * @param locationOffered whether the location is one the web's picker offers
   * @param method the refining method, or {@code null} when the op names none
   * @param orgUnit the owning unit of a create, or {@code null}
   * @param inputs the input materials in the order the op names them
   */
  record Refs(
      @Nullable Outcome refusal,
      @Nullable UUID location,
      boolean locationOffered,
      @Nullable UUID method,
      @Nullable UUID orgUnit,
      @NotNull @Unmodifiable List<UUID> inputs) {

    /**
     * The references of an op one of them refuses.
     *
     * @param refusal the refusal
     * @return the references
     */
    static @NotNull Refs refused(@NotNull Outcome refusal) {
      return new Refs(refusal, null, false, null, null, List.of());
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
