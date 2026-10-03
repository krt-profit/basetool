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

package de.greluc.krt.profit.basetool.backend.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

/**
 * Distributes the stock linked to one order and one material across that order's quality demands so
 * every unit counts exactly once (REQ-ORDERS-037).
 *
 * <p>Demands are served from the highest floor down; each takes the lowest qualifying quality
 * first. Stock beyond every need goes to the highest-floor demand it satisfies. Stock below every
 * floor stays unattributed. Pure and stateless.
 */
public final class QualityBucketAllocator {

  /** Below this, a remaining need or supply counts as exhausted. */
  private static final double EPSILON = 1e-9;

  private QualityBucketAllocator() {}

  /**
   * One quality demand of the order for the material.
   *
   * @param key the caller's identity of the demand, returned in the result
   * @param floor the lowest stock quality that satisfies it
   * @param need the outstanding amount; a negative value counts as 0
   * @param <K> the key type
   */
  public record Demand<K>(@NotNull K key, int floor, double need) {}

  /**
   * One linked stock row: its quality and the amount earmarked to the order.
   *
   * @param rowId the inventory row
   * @param quality the row's quality; {@code null} counts as 0
   * @param amount the earmarked amount; a negative value counts as 0
   */
  public record Supply(@NotNull UUID rowId, @Nullable Integer quality, double amount) {}

  /**
   * The outcome of one distribution.
   *
   * @param byDemand the amount attributed to each demand key, every key present
   * @param byRow per stock row, the amount attributed to each demand key; rows absent or partly
   *     absent are unattributed
   * @param unattributed the total stock no demand accepts
   * @param <K> the key type
   */
  public record Allocation<K>(
      @UnmodifiableView @NotNull Map<K, Double> byDemand,
      @UnmodifiableView @NotNull Map<UUID, Map<K, Double>> byRow,
      double unattributed) {

    /**
     * Returns the amount attributed to one demand.
     *
     * @param key the demand key
     * @return the attributed amount, {@code 0} for an unknown key
     */
    public double attributedTo(@NotNull K key) {
      return byDemand.getOrDefault(key, 0.0);
    }
  }

  /**
   * Distributes the supplies over the demands.
   *
   * @param demands the order's demands for one material; equal floors are served in list order
   * @param supplies the stock rows linked to the order for that material
   * @param <K> the demand key type; keys must be distinct
   * @return the distribution, never {@code null}
   * @throws IllegalArgumentException when two demands share a key
   */
  @NotNull
  public static <K> Allocation<K> allocate(
      @NotNull List<Demand<K>> demands, @NotNull List<Supply> supplies) {
    Map<K, Double> byDemand = new LinkedHashMap<>();
    for (Demand<K> demand : demands) {
      if (byDemand.put(demand.key(), 0.0) != null) {
        throw new IllegalArgumentException("Duplicate demand key: " + demand.key());
      }
    }
    List<Demand<K>> highestFirst = new ArrayList<>(demands);
    highestFirst.sort(Comparator.comparingInt((Demand<K> d) -> d.floor()).reversed());

    List<Supply> lowestFirst = new ArrayList<>(supplies);
    lowestFirst.sort(
        Comparator.comparingInt((Supply s) -> quality(s))
            .thenComparing(Supply::rowId, Comparator.naturalOrder()));
    double[] left = new double[lowestFirst.size()];
    for (int i = 0; i < left.length; i++) {
      left[i] = Math.max(0.0, lowestFirst.get(i).amount());
    }
    Map<UUID, Map<K, Double>> byRow = new LinkedHashMap<>();

    for (Demand<K> demand : highestFirst) {
      double need = Math.max(0.0, demand.need());
      for (int i = 0; i < left.length && need > EPSILON; i++) {
        if (left[i] <= EPSILON || quality(lowestFirst.get(i)) < demand.floor()) {
          continue;
        }
        double take = Math.min(need, left[i]);
        assign(byDemand, byRow, demand.key(), lowestFirst.get(i).rowId(), take);
        left[i] -= take;
        need -= take;
      }
    }

    double unattributed = 0.0;
    for (int i = 0; i < left.length; i++) {
      if (left[i] <= EPSILON) {
        continue;
      }
      Demand<K> home = highestSatisfied(highestFirst, quality(lowestFirst.get(i)));
      if (home == null) {
        unattributed += left[i];
      } else {
        assign(byDemand, byRow, home.key(), lowestFirst.get(i).rowId(), left[i]);
      }
    }
    return new Allocation<>(
        Collections.unmodifiableMap(byDemand), freeze(byRow), Math.max(0.0, unattributed));
  }

  /**
   * Finds the demand with the highest floor a quality still satisfies; the first in list order
   * among equal floors.
   *
   * @param highestFirst the demands, highest floor first, stable within a floor
   * @param quality the stock quality
   * @param <K> the key type
   * @return the demand, or {@code null} when the quality satisfies none
   */
  @Nullable
  private static <K> Demand<K> highestSatisfied(
      @NotNull List<Demand<K>> highestFirst, int quality) {
    for (Demand<K> demand : highestFirst) {
      if (quality >= demand.floor()) {
        return demand;
      }
    }
    return null;
  }

  /**
   * Books an attributed amount on the demand and on the row.
   *
   * @param byDemand the per-demand totals
   * @param byRow the per-row attributions
   * @param key the demand
   * @param rowId the row
   * @param amount the amount
   * @param <K> the key type
   */
  private static <K> void assign(
      @NotNull Map<K, Double> byDemand,
      @NotNull Map<UUID, Map<K, Double>> byRow,
      @NotNull K key,
      @NotNull UUID rowId,
      double amount) {
    byDemand.merge(key, amount, Double::sum);
    byRow.computeIfAbsent(rowId, unused -> new LinkedHashMap<>()).merge(key, amount, Double::sum);
  }

  /**
   * Makes the per-row map and its inner maps unmodifiable.
   *
   * @param byRow the mutable per-row map
   * @param <K> the key type
   * @return the frozen copy
   */
  @NotNull
  private static <K> Map<UUID, Map<K, Double>> freeze(@NotNull Map<UUID, Map<K, Double>> byRow) {
    Map<UUID, Map<K, Double>> frozen = new LinkedHashMap<>();
    byRow.forEach((row, keys) -> frozen.put(row, Collections.unmodifiableMap(keys)));
    return Collections.unmodifiableMap(frozen);
  }

  /**
   * Reads a supply's quality, {@code null} as 0.
   *
   * @param supply the supply
   * @return the quality
   */
  private static int quality(@NotNull Supply supply) {
    return supply.quality() == null ? 0 : supply.quality();
  }
}
