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
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeCatalogKind;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeItemRef;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeResolveRequest;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeResolveResponse;
import java.util.ArrayList;
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
 * Resolves the material or item of the lots a client writes: each reference as an exact material
 * first, and as an exact item where no material matched exactly (REQ-XCH-016, REQ-XCH-045).
 */
@Component
@RequiredArgsConstructor
public class ExchangeLotCatalogue {

  /** The result of a reference that resolves to nothing. */
  static final String UNMATCHED = "unmatched";

  /** The result of a reference that resolves to several entries. */
  static final String AMBIGUOUS = "ambiguous";

  private final ExchangeResolveService resolveService;
  private final MaterialRepository materialRepository;
  private final GameItemRepository gameItemRepository;

  /**
   * Resolves each reference: an exact material, else an exact item, else {@code ambiguous} where
   * either catalogue answered ambiguous (several matches or fuzzy suggestions), else {@code
   * unmatched}. A fuzzy material suggestion never hides an exact item.
   *
   * @param refs the references, in op order
   * @return the material or item, or the result that ends the op, by the reference's index
   */
  public @NotNull Map<Integer, Entry> resolve(@NotNull List<ExchangeItemRef> refs) {
    ExchangeResolveResponse materials =
        resolveService.resolve(new ExchangeResolveRequest(ExchangeCatalogKind.MATERIAL, refs));
    Map<Integer, Entry> byIndex = new HashMap<>();
    List<Integer> retry = new ArrayList<>();
    Set<Integer> ambiguousMaterial = new HashSet<>();
    for (ExchangeResolveResponse.Result result : materials.results()) {
      if (result.status() == ExchangeResolveResponse.Status.RESOLVED) {
        Material material = materialRepository.findById(resolvedId(result)).orElseThrow();
        byIndex.put(result.index(), Entry.of(material));
      } else {
        if (result.status() == ExchangeResolveResponse.Status.AMBIGUOUS) {
          ambiguousMaterial.add(result.index());
        }
        retry.add(result.index());
      }
    }
    if (!retry.isEmpty()) {
      ExchangeResolveResponse items =
          resolveService.resolve(
              new ExchangeResolveRequest(
                  ExchangeCatalogKind.ITEM, retry.stream().map(refs::get).toList()));
      for (ExchangeResolveResponse.Result result : items.results()) {
        int index = retry.get(result.index());
        if (result.status() == ExchangeResolveResponse.Status.RESOLVED) {
          GameItem item = gameItemRepository.findById(resolvedId(result)).orElseThrow();
          byIndex.put(index, Entry.of(item));
        } else if (result.status() == ExchangeResolveResponse.Status.AMBIGUOUS
            || ambiguousMaterial.contains(index)) {
          byIndex.put(index, Entry.skip(AMBIGUOUS, "AMBIGUOUS"));
        } else {
          byIndex.put(index, Entry.skip(UNMATCHED, "UNMATCHED"));
        }
      }
    }
    return byIndex;
  }

  /**
   * Reads the Basetool id of a resolved result.
   *
   * @param result a result whose status is {@code RESOLVED}
   * @return the id of the entry it resolved to
   */
  private static @NotNull UUID resolvedId(@NotNull ExchangeResolveResponse.Result result) {
    return UUID.fromString(Objects.requireNonNull(result.ref()).bt());
  }

  /**
   * A resolved material or item, or the result that ends the op.
   *
   * @param material the material, or {@code null}
   * @param gameItem the item, or {@code null}
   * @param unit the lot's unit, {@code SCU} or {@code PIECE}
   * @param result the result that ends the op, or {@code null}
   * @param reason the reason of that result, or {@code null}
   */
  public record Entry(
      @Nullable Material material,
      @Nullable GameItem gameItem,
      @NotNull String unit,
      @Nullable String result,
      @Nullable String reason) {

    /**
     * A material, counted in pieces or SCU by its quantity type.
     *
     * @param material the material
     * @return the entry
     */
    static @NotNull Entry of(@NotNull Material material) {
      String unit =
          material.getQuantityType() == QuantityType.PIECE
              ? QuantityType.PIECE.name()
              : QuantityType.SCU.name();
      return new Entry(material, null, unit, null, null);
    }

    /**
     * An item, counted in pieces.
     *
     * @param item the item
     * @return the entry
     */
    static @NotNull Entry of(@NotNull GameItem item) {
      return new Entry(null, item, QuantityType.PIECE.name(), null, null);
    }

    /**
     * The end of an op.
     *
     * @param result the result
     * @param reason the reason
     * @return the entry
     */
    static @NotNull Entry skip(@NotNull String result, @NotNull String reason) {
      return new Entry(null, null, QuantityType.SCU.name(), result, reason);
    }
  }
}
