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

import static de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeOps.AMBIGUOUS;
import static de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeOps.UNMATCHED;

import de.greluc.krt.profit.basetool.backend.catalogue.api.OfferedShipTypes;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeCatalogKind;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeItemRef;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeResolveRequest;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeResolveResponse;
import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Resolves the ship types a ship change set's upserts name, in one resolver call, and tells which
 * of them the pickers offer (REQ-XCH-017, REQ-P4K-080).
 */
@Component
@RequiredArgsConstructor
class ExchangeShipTypeResolution {

  private final ExchangeResolveService resolveService;
  private final ShipTypeRepository shipTypeRepository;

  /**
   * Resolves ship-type references.
   *
   * @param refs the references, one per upsert
   * @return the match of each reference, by its index in {@code refs}
   */
  @NotNull
  Map<Integer, Match> resolve(@NotNull List<ExchangeItemRef> refs) {
    Map<Integer, Match> byRef = new HashMap<>();
    if (refs.isEmpty()) {
      return byRef;
    }
    ExchangeResolveResponse response =
        resolveService.resolve(new ExchangeResolveRequest(ExchangeCatalogKind.SHIP_TYPE, refs));
    Set<UUID> offered = offeredOf(response.results());
    for (ExchangeResolveResponse.Result result : response.results()) {
      byRef.put(
          result.index(),
          switch (result.status()) {
            case RESOLVED -> {
              UUID shipTypeId = idOf(result);
              yield new Match(shipTypeId, offered.contains(shipTypeId), null, null);
            }
            case AMBIGUOUS -> new Match(null, false, AMBIGUOUS, ExchangeReasons.AMBIGUOUS);
            case UNMATCHED -> new Match(null, false, UNMATCHED, ExchangeReasons.UNMATCHED);
          });
    }
    return byRef;
  }

  /**
   * Returns which of the resolved ship types the pickers offer, in one query.
   *
   * @param results the resolver's results
   * @return the ids of the resolved ship types the pickers offer
   */
  @NotNull
  private Set<UUID> offeredOf(@NotNull List<ExchangeResolveResponse.Result> results) {
    Set<UUID> resolved = new HashSet<>();
    for (ExchangeResolveResponse.Result result : results) {
      if (result.status() == ExchangeResolveResponse.Status.RESOLVED) {
        resolved.add(idOf(result));
      }
    }
    Set<UUID> offered = new HashSet<>();
    if (!resolved.isEmpty()) {
      shipTypeRepository.findAllById(resolved).stream()
          .filter(OfferedShipTypes::isOffered)
          .forEach(shipType -> offered.add(shipType.getId()));
    }
    return offered;
  }

  /**
   * Reads the ship type id a resolved result names.
   *
   * @param result a result with status {@code RESOLVED}
   * @return the ship type id
   */
  @NotNull
  private static UUID idOf(@NotNull ExchangeResolveResponse.Result result) {
    return UUID.fromString(Objects.requireNonNull(result.ref()).bt());
  }

  /**
   * A resolved ship type, or the result that ends the op.
   *
   * @param shipTypeId the ship type, or {@code null}
   * @param offered whether the pickers offer the ship type
   * @param result the result that ends the op, or {@code null}
   * @param reason the reason of that result, or {@code null}
   */
  record Match(
      @Nullable UUID shipTypeId,
      boolean offered,
      @Nullable String result,
      @Nullable String reason) {

    /**
     * Whether a write may set the ship type on a ship: it is offered, or it is the ship's current
     * type.
     *
     * @param current the ship as it is, or {@code null} for a new one
     * @return {@code true} when the hangar would accept the type
     */
    boolean acceptableFor(@Nullable Ship current) {
      return offered
          || (current != null && Objects.equals(current.getShipType().getId(), shipTypeId));
    }
  }
}
