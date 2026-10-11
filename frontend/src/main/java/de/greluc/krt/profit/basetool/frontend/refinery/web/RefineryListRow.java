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

package de.greluc.krt.profit.basetool.frontend.refinery.web;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryGoodDto;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderListDto;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One row of the refinery list: the order with its run state, progress and the yield and input
 * summaries the row prints (REQ-REFINERY-019).
 *
 * @param order the order the row shows
 * @param state where the order stands in its run
 * @param endsAt the end of the run, or {@code null} when unknown
 * @param progressPercent how far the run has come, {@code 0..100}
 * @param remaining the time left of a running order, formatted; {@code null} otherwise
 * @param readySince the time since a ready order's end, formatted; {@code null} when not ready or
 *     the end is unknown
 * @param shortId the last four characters of the order id, the row's handle
 * @param outputLabel the yield per output material in SCU, e.g. "Laranite 9,2 · Agricium 4,1 SCU"
 * @param inputCount the number of distinct input materials
 * @param inputName the single input material's name, or {@code null} unless there is exactly one
 * @param inputUnits the input quantity of every good, in units
 * @param storable whether the viewer may store the yield: an open order the viewer owns or manages
 */
public record RefineryListRow(
    @NotNull RefineryOrderListDto order,
    @NotNull RefineryProgress.State state,
    @Nullable Instant endsAt,
    int progressPercent,
    @Nullable String remaining,
    @Nullable String readySince,
    @NotNull String shortId,
    @NotNull String outputLabel,
    int inputCount,
    @Nullable String inputName,
    long inputUnits,
    boolean storable) {

  /** Units in one SCU. */
  private static final double UNITS_PER_SCU = 100.0;

  /** Length of the id suffix a row shows. */
  private static final int SHORT_ID_LENGTH = 4;

  /** Printed when an order has no goods to summarise. */
  private static final String NONE = "—";

  /**
   * Builds the row of an order at {@code now}.
   *
   * @param order the order
   * @param now the instant the state and progress are judged at
   * @param viewerId the viewing member's id, or {@code null} when unknown
   * @param manager whether the viewer may manage other members' orders (logistician and up)
   * @param locale the locale the SCU figures are formatted in
   * @return the row
   */
  @NotNull
  public static RefineryListRow of(
      @NotNull RefineryOrderListDto order,
      @NotNull Instant now,
      @Nullable UUID viewerId,
      boolean manager,
      @NotNull Locale locale) {
    Instant endsAt = RefineryProgress.endsAt(order.startedAt(), order.durationMinutes());
    RefineryProgress.State state = RefineryProgress.state(order.status(), endsAt, now);
    String remaining =
        state == RefineryProgress.State.RUNNING && endsAt != null
            ? RefineryProgress.formatDuration(RefineryProgress.minutesUntil(endsAt, now))
            : null;
    String readySince =
        state == RefineryProgress.State.READY && endsAt != null
            ? RefineryProgress.formatDuration(RefineryProgress.minutesSince(endsAt, now))
            : null;
    boolean open = state == RefineryProgress.State.RUNNING || state == RefineryProgress.State.READY;
    boolean owner =
        viewerId != null && order.owner() != null && viewerId.equals(order.owner().id());
    List<RefineryGoodDto> goods = order.goods() == null ? List.of() : order.goods();
    Set<String> inputs = new LinkedHashSet<>();
    long inputUnits = 0;
    for (RefineryGoodDto good : goods) {
      if (good.inputMaterial() != null && good.inputMaterial().name() != null) {
        inputs.add(good.inputMaterial().name());
      }
      if (good.inputQuantity() != null) {
        inputUnits += good.inputQuantity();
      }
    }
    return new RefineryListRow(
        order,
        state,
        endsAt,
        RefineryProgress.progressPercent(order.startedAt(), order.durationMinutes(), now),
        remaining,
        readySince,
        shortId(order.id()),
        outputLabel(goods, locale),
        inputs.size(),
        inputs.size() == 1 ? inputs.iterator().next() : null,
        inputUnits,
        open && (manager || owner));
  }

  /**
   * Whether the row is ready for pickup.
   *
   * @return true for a {@link RefineryProgress.State#READY} row
   */
  @Contract(pure = true)
  public boolean ready() {
    return state == RefineryProgress.State.READY;
  }

  @Nullable
  private static String nameOf(@Nullable MaterialDto material) {
    return material == null ? null : material.name();
  }

  @NotNull
  private static String shortId(@Nullable UUID id) {
    if (id == null) {
      return NONE;
    }
    String text = id.toString();
    return text.substring(text.length() - SHORT_ID_LENGTH);
  }

  @NotNull
  private static String outputLabel(@NotNull List<RefineryGoodDto> goods, @NotNull Locale locale) {
    Map<String, Long> unitsByMaterial = new LinkedHashMap<>();
    for (RefineryGoodDto good : goods) {
      String name = nameOf(good.outputMaterial());
      if (name == null) {
        name = nameOf(good.inputMaterial());
      }
      if (name == null) {
        continue;
      }
      long units = good.outputQuantity() == null ? 0 : good.outputQuantity();
      unitsByMaterial.merge(name, units, Long::sum);
    }
    if (unitsByMaterial.isEmpty()) {
      return NONE;
    }
    NumberFormat scu = NumberFormat.getNumberInstance(locale);
    scu.setMinimumFractionDigits(1);
    scu.setMaximumFractionDigits(1);
    List<String> parts = new ArrayList<>();
    unitsByMaterial.forEach(
        (name, units) -> parts.add(name + " " + scu.format(units / UNITS_PER_SCU)));
    return String.join(" · ", parts) + " SCU";
  }
}
