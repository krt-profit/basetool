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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Effects;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Execution;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Outcome;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Planned;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeChangeResultDto;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ExchangeChangeSetRunnerTest {

  private static final ExchangeCaller CALLER =
      new ExchangeCaller(UUID.randomUUID(), "versekit", "k".repeat(43));

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final ExchangeChangeSetRunner runner = new ExchangeChangeSetRunner(registry);

  @ParameterizedTest
  @EnumSource(ExchangeResource.class)
  void aRealRunCountsEveryOpUnderItsResultForEveryResource(ExchangeResource resource) {
    FakeAdapter adapter = new FakeAdapter(resource, false);

    ExchangeChangeResultDto result = runner.run(CALLER, adapter, new FakeChangeSet(false), true);

    assertThat(result.applied()).isEqualTo(2);
    assertThat(result.unchanged()).isEqualTo(2);
    assertThat(result.notApplied()).isEqualTo(2);
    assertThat(count(resource, "applied")).isEqualTo(2.0);
    assertThat(count(resource, "unchanged")).isEqualTo(2.0);
    assertThat(count(resource, "rejected")).isEqualTo(1.0);
    assertThat(count(resource, "unmatched")).isEqualTo(1.0);
    assertThat(count(resource, "held")).isZero();
    assertThat(adapter.ran).containsExactly("apply", "apply", "unchanged", "refuse");
    assertThat(adapter.announced).isTrue();
  }

  @ParameterizedTest
  @EnumSource(ExchangeResource.class)
  void aDryRunReportsTheSameCountsButCountsNothing(ExchangeResource resource) {
    FakeAdapter adapter = new FakeAdapter(resource, true);

    ExchangeChangeResultDto result = runner.run(CALLER, adapter, new FakeChangeSet(true), true);

    assertThat(result.dryRun()).isTrue();
    assertThat(result.applied()).isEqualTo(2);
    assertThat(result.unchanged()).isEqualTo(2);
    assertThat(result.notApplied()).isEqualTo(2);
    assertThat(registry.find(MetricNames.EXCHANGE_WRITES).counters()).isEmpty();
    assertThat(adapter.ran).containsExactly("apply", "apply", "unchanged", "refuse");
    assertThat(adapter.announced).isFalse();
  }

  @ParameterizedTest
  @EnumSource(ExchangeResource.class)
  void aHeldChangeSetCountsOneHeldAndWritesNothing(ExchangeResource resource) {
    FakeAdapter adapter = new FakeAdapter(resource, false);
    adapter.holdBack = true;

    assertThatThrownBy(() -> runner.run(CALLER, adapter, new FakeChangeSet(false), true))
        .isInstanceOf(ExchangeProblemException.class);

    assertThat(count(resource, "held")).isEqualTo(1.0);
    assertThat(registry.find(MetricNames.EXCHANGE_WRITES).counters()).hasSize(1);
    assertThat(adapter.started).isFalse();
  }

  @Test
  void aConfirmedChangeSetAndADryRunNeverAskTheGuard() {
    FakeAdapter confirmed = new FakeAdapter(ExchangeResource.SHIP, false);
    confirmed.holdBack = true;
    FakeAdapter dryRun = new FakeAdapter(ExchangeResource.SHIP, true);
    dryRun.holdBack = true;

    runner.run(CALLER, confirmed, new FakeChangeSet(false), false);
    runner.run(CALLER, dryRun, new FakeChangeSet(true), true);

    assertThat(confirmed.asked).isFalse();
    assertThat(dryRun.asked).isFalse();
    assertThat(count(ExchangeResource.SHIP, "held")).isZero();
  }

  @Test
  void theResultListsEveryOpNotAppliedWithItsIndexAndIdAndTheEffects() {
    ExchangeChangeResultDto result =
        runner.run(
            CALLER, new FakeAdapter(ExchangeResource.STOCK, false), new FakeChangeSet(false), true);

    assertThat(result.results())
        .containsExactly(
            new ExchangeChangeResultDto.OpResult(1, "op-1", "unchanged", null),
            new ExchangeChangeResultDto.OpResult(2, "op-2", "unmatched", "UNMATCHED"),
            new ExchangeChangeResultDto.OpResult(4, "op-4", "unchanged", null),
            new ExchangeChangeResultDto.OpResult(5, "op-5", "rejected", "NOT_PERMITTED"));
    assertThat(result.results()).isUnmodifiable();
    assertThat(result.offersReduced()).isEqualTo(3);
    assertThat(result.offersRemoved()).isEqualTo(1);
    assertThat(result.detachedFromMissions()).isNull();
  }

  @Test
  void aRunThatAppliesNothingAnnouncesNothing() {
    FakeAdapter adapter = new FakeAdapter(ExchangeResource.BLUEPRINT, false);
    adapter.planned = List.of(ExchangeChangeSetAdapter.skip("unchanged", null));

    runner.run(CALLER, adapter, new FakeChangeSet(false), true);

    assertThat(adapter.announced).isFalse();
  }

  private double count(ExchangeResource resource, String outcome) {
    Counter counter =
        registry
            .find(MetricNames.EXCHANGE_WRITES)
            .tags(
                MetricNames.TAG_CLIENT_ID,
                CALLER.clientId(),
                MetricNames.TAG_RESOURCE,
                resource.name().toLowerCase(java.util.Locale.ROOT),
                MetricNames.TAG_OUTCOME,
                outcome)
            .counter();
    return counter == null ? 0.0 : counter.count();
  }

  private record FakeChangeSet(boolean dryRun) {}

  private static final class FakeAdapter
      implements ExchangeChangeSetAdapter<FakeChangeSet, String> {

    private final ExchangeResource resource;
    private final boolean expectedDryRun;
    private final List<String> ran = new ArrayList<>();
    private List<Planned<String>> planned =
        List.of(
            ExchangeChangeSetAdapter.write("apply"),
            ExchangeChangeSetAdapter.skip("unchanged", null),
            ExchangeChangeSetAdapter.skip("unmatched", "UNMATCHED"),
            ExchangeChangeSetAdapter.write("apply"),
            ExchangeChangeSetAdapter.write("unchanged"),
            ExchangeChangeSetAdapter.write("refuse"));
    private boolean holdBack;
    private boolean asked;
    private boolean started;
    private boolean announced;

    private FakeAdapter(ExchangeResource resource, boolean expectedDryRun) {
      this.resource = resource;
      this.expectedDryRun = expectedDryRun;
    }

    @Override
    public @NotNull ExchangeResource resource() {
      return resource;
    }

    @Override
    public boolean dryRun(@NotNull FakeChangeSet changeSet) {
      return changeSet.dryRun();
    }

    @Override
    public @Nullable String opId(@NotNull FakeChangeSet changeSet, int index) {
      return "op-" + index;
    }

    @Override
    public @NotNull List<Planned<String>> plan(
        @NotNull ExchangeCaller caller, @NotNull FakeChangeSet changeSet) {
      return planned;
    }

    @Override
    public boolean holdsBack(
        @NotNull ExchangeCaller caller,
        @NotNull FakeChangeSet changeSet,
        @NotNull List<Planned<String>> plan) {
      asked = true;
      return holdBack;
    }

    @Override
    public @NotNull Execution<String> start(
        @NotNull ExchangeCaller caller,
        @NotNull UUID batch,
        @NotNull List<Planned<String>> plan,
        boolean dryRun) {
      assertThat(dryRun).isEqualTo(expectedDryRun);
      started = true;
      return new Execution<>() {
        @Override
        public @NotNull Outcome run(@NotNull String change) {
          ran.add(change);
          return switch (change) {
            case "apply" -> Outcome.APPLIED;
            case "unchanged" -> Outcome.UNCHANGED;
            default -> Outcome.rejected("NOT_PERMITTED");
          };
        }

        @Override
        public @NotNull Effects effects() {
          return resource == ExchangeResource.STOCK ? new Effects(3, 1, null) : Effects.NONE;
        }

        @Override
        public void announce() {
          announced = true;
        }
      };
    }
  }
}
