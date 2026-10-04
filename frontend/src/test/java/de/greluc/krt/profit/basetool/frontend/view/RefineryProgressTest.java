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

package de.greluc.krt.profit.basetool.frontend.view;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryGoodDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderListDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RefineryProgress} and {@link RefineryListRow}: run state, progress,
 * duration format, the yield and input summaries, the store permission and the search.
 */
class RefineryProgressTest {

  /** The instant every case is judged at. */
  private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

  /** The viewing member. */
  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-00000000cccc");

  /**
   * A material of the catalogue.
   *
   * @param name the material name
   * @return the material
   */
  private static @NotNull MaterialDto material(@NotNull String name) {
    return new MaterialDto(
        UUID.randomUUID(),
        name,
        null,
        "SCU",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        1L);
  }

  /**
   * A good of a run.
   *
   * @param ore the input material name
   * @param refined the output material name, or {@code null}
   * @param in the input units
   * @param out the output units
   * @return the good
   */
  private static @NotNull RefineryGoodDto good(
      @NotNull String ore, @Nullable String refined, int in, int out) {
    return new RefineryGoodDto(
        UUID.randomUUID(),
        material(ore),
        in,
        refined == null ? null : material(refined),
        out,
        500,
        null);
  }

  /**
   * An order of {@code owner}.
   *
   * @param owner the owner id
   * @param status the backend status
   * @param startedAt the start, or {@code null}
   * @param duration the duration in minutes, or {@code null}
   * @param goods the goods
   * @return the order
   */
  private static @NotNull RefineryOrderListDto order(
      @NotNull UUID owner,
      @NotNull String status,
      @Nullable Instant startedAt,
      @Nullable Long duration,
      @NotNull List<RefineryGoodDto> goods) {
    return new RefineryOrderListDto(
        UUID.fromString("00000000-0000-0000-0000-000000001047"),
        new UserReferenceDto(owner, "vexx", null, "Vexx", 0),
        new LocationDto(
            UUID.randomUUID(), "CRU-L1 Ambitious Dream Station", null, false, false, 1L),
        null,
        startedAt,
        duration,
        0d,
        0d,
        0d,
        0d,
        null,
        status,
        goods,
        null,
        1L);
  }

  /** An open order is running until its end, ready from it on, and ready without a known end. */
  @Test
  void stateFollowsTheEnd() {
    assertThat(RefineryProgress.state("OPEN", NOW.plusSeconds(60), NOW))
        .isEqualTo(RefineryProgress.State.RUNNING);
    assertThat(RefineryProgress.state("IN_PROGRESS", NOW, NOW))
        .isEqualTo(RefineryProgress.State.READY);
    assertThat(RefineryProgress.state("OPEN", null, NOW)).isEqualTo(RefineryProgress.State.READY);
    assertThat(RefineryProgress.state("COMPLETED", NOW.plusSeconds(60), NOW))
        .isEqualTo(RefineryProgress.State.COMPLETED);
    assertThat(RefineryProgress.state("CANCELED", null, NOW))
        .isEqualTo(RefineryProgress.State.CANCELED);
  }

  /** Progress is the elapsed share of the duration, clamped to 0..100. */
  @Test
  void progressIsClamped() {
    assertThat(RefineryProgress.progressPercent(NOW.minusSeconds(30 * 60), 60L, NOW)).isEqualTo(50);
    assertThat(RefineryProgress.progressPercent(NOW.plusSeconds(60), 60L, NOW)).isZero();
    assertThat(RefineryProgress.progressPercent(NOW.minusSeconds(7200), 60L, NOW)).isEqualTo(100);
    assertThat(RefineryProgress.progressPercent(null, 60L, NOW)).isEqualTo(100);
  }

  /** Durations read in minutes, hours and days; time left rounds up, time since rounds down. */
  @Test
  void formatsDurations() {
    assertThat(RefineryProgress.formatDuration(45)).isEqualTo("45 min");
    assertThat(RefineryProgress.formatDuration(200)).isEqualTo("3 h 20 min");
    assertThat(RefineryProgress.formatDuration(120)).isEqualTo("2 h");
    assertThat(RefineryProgress.formatDuration(1680)).isEqualTo("1 d 4 h");
    assertThat(RefineryProgress.formatDuration(-5)).isEqualTo("0 min");
    assertThat(RefineryProgress.minutesUntil(NOW.plusSeconds(61), NOW)).isEqualTo(2);
    assertThat(RefineryProgress.minutesSince(NOW.minusSeconds(119), NOW)).isEqualTo(1);
  }

  /** A row sums the yield per output in SCU and summarises its inputs. */
  @Test
  void rowSummarisesYieldAndInputs() {
    RefineryListRow row =
        RefineryListRow.of(
            order(
                VIEWER,
                "OPEN",
                NOW.minusSeconds(3600),
                30L,
                List.of(
                    good("Laranite-Erz", "Laranite", 1000, 920),
                    good("Agricium-Erz", "Agricium", 650, 410))),
            NOW,
            VIEWER,
            false,
            Locale.GERMAN);

    assertThat(row.state()).isEqualTo(RefineryProgress.State.READY);
    assertThat(row.ready()).isTrue();
    assertThat(row.readySince()).isEqualTo("30 min");
    assertThat(row.remaining()).isNull();
    assertThat(row.shortId()).isEqualTo("1047");
    assertThat(row.outputLabel()).isEqualTo("Laranite 9,2 · Agricium 4,1 SCU");
    assertThat(row.inputCount()).isEqualTo(2);
    assertThat(row.inputName()).isNull();
    assertThat(row.inputUnits()).isEqualTo(1650);
    assertThat(row.storable()).isTrue();
  }

  /** Only the owner or a manager may store, and only an open run. */
  @Test
  void storableNeedsOwnerOrManagerAndAnOpenRun() {
    RefineryOrderListDto foreign =
        order(UUID.randomUUID(), "OPEN", null, null, List.of(good("Gold-Erz", "Gold", 100, 90)));
    assertThat(RefineryListRow.of(foreign, NOW, VIEWER, false, Locale.GERMAN).storable()).isFalse();
    assertThat(RefineryListRow.of(foreign, NOW, VIEWER, true, Locale.GERMAN).storable()).isTrue();
    RefineryOrderListDto stored =
        order(VIEWER, "COMPLETED", null, null, List.of(good("Gold-Erz", "Gold", 100, 90)));
    assertThat(RefineryListRow.of(stored, NOW, VIEWER, true, Locale.GERMAN).storable()).isFalse();
  }

  /** A running row carries its time left and an output without a refined material names its ore. */
  @Test
  void runningRowAndOreOutput() {
    RefineryListRow row =
        RefineryListRow.of(
            order(VIEWER, "OPEN", NOW, 90L, List.of(good("Inert Materials", null, 640, 0))),
            NOW,
            VIEWER,
            false,
            Locale.GERMAN);

    assertThat(row.state()).isEqualTo(RefineryProgress.State.RUNNING);
    assertThat(row.remaining()).isEqualTo("1 h 30 min");
    assertThat(row.progressPercent()).isZero();
    assertThat(row.outputLabel()).isEqualTo("Inert Materials 0,0 SCU");
    assertThat(row.inputName()).isEqualTo("Inert Materials");
  }
}
