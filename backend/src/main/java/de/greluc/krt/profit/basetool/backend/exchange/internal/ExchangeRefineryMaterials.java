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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Outcome;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeCatalogKind;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeItemRef;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeResolveRequest;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeResolveResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Resolves the material references of a refinery change set exactly, in one resolver call and one
 * read (REQ-XCH-052): a good's input and a store line's material alike.
 */
@Component
@RequiredArgsConstructor
class ExchangeRefineryMaterials {

  private final ExchangeResolveService resolveService;
  private final MaterialRepository materialRepository;

  /**
   * Resolves every distinct reference; references that name one material alike each map to it.
   *
   * @param refs the references, duplicates allowed
   * @return per reference its material or the outcome of a reference that names no single one
   */
  @NotNull
  Map<ExchangeItemRef, Resolved> resolve(@NotNull Collection<ExchangeItemRef> refs) {
    List<ExchangeItemRef> distinct = List.copyOf(new LinkedHashSet<>(refs));
    Map<ExchangeItemRef, Resolved> resolved = new HashMap<>();
    if (distinct.isEmpty()) {
      return resolved;
    }
    ExchangeResolveResponse response =
        resolveService.resolve(new ExchangeResolveRequest(ExchangeCatalogKind.MATERIAL, distinct));
    Map<UUID, List<ExchangeItemRef>> byId = new HashMap<>();
    for (ExchangeResolveResponse.Result result : response.results()) {
      ExchangeItemRef ref = distinct.get(result.index());
      switch (result.status()) {
        case RESOLVED ->
            byId.computeIfAbsent(
                    UUID.fromString(Objects.requireNonNull(result.ref()).bt()),
                    _ -> new ArrayList<>())
                .add(ref);
        case AMBIGUOUS ->
            resolved.put(
                ref,
                Resolved.refused(new Outcome(ExchangeOps.AMBIGUOUS, ExchangeReasons.AMBIGUOUS)));
        case UNMATCHED -> resolved.put(ref, Resolved.UNMATCHED);
        case null -> throw new IllegalStateException("A resolver result without a status");
      }
    }
    for (Material material : materialRepository.findAllById(byId.keySet())) {
      Resolved found = new Resolved(material, null);
      byId.getOrDefault(material.getId(), List.of()).forEach(ref -> resolved.put(ref, found));
    }
    byId.values().stream()
        .flatMap(List::stream)
        .forEach(ref -> resolved.putIfAbsent(ref, Resolved.UNMATCHED));
    return resolved;
  }

  /**
   * The material a reference names, or the outcome of one that names none.
   *
   * @param material the material, or {@code null}
   * @param refusal the outcome, or {@code null}
   */
  record Resolved(@Nullable Material material, @Nullable Outcome refusal) {

    /** A reference that names nothing. */
    static final Resolved UNMATCHED =
        refused(new Outcome(ExchangeOps.UNMATCHED, ExchangeReasons.UNMATCHED));

    /**
     * A reference that ends its op.
     *
     * @param refusal the outcome
     * @return the resolution
     */
    static @NotNull Resolved refused(@NotNull Outcome refusal) {
      return new Resolved(null, refusal);
    }
  }
}
