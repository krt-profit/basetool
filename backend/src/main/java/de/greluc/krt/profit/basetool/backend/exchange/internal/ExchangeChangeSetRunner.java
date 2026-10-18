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

import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Execution;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Outcome;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Planned;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Skip;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Write;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeChangeResultDto;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Runs one exchange change set for any resource: plans it through the resource's adapter, lets the
 * mass-change guard hold it back, hands every planned change to the adapter, counts each op's
 * outcome and builds the result (REQ-XCH-015, REQ-XCH-021, ADR-0285).
 *
 * <p>A dry run counts nothing; a held change set counts one {@code held} and writes nothing.
 */
@Component
@RequiredArgsConstructor
public class ExchangeChangeSetRunner {

  private final MeterRegistry meterRegistry;

  /**
   * Plans and runs one change set in the caller's transaction.
   *
   * @param caller the client, installation and member
   * @param adapter the resource's adapter
   * @param changeSet the change set
   * @param guarded whether the mass-change guard decides; {@code false} for a confirmed change set
   * @param <S> the resource's change set
   * @param <C> the resource's planned change
   * @return the counts, the resource's effects and the detail of every op that was not applied
   * @throws ExchangeProblemException {@code 409 MASS_CHANGE_CONFIRMATION_REQUIRED} when the guard
   *     holds the change set back; nothing is written then
   */
  public <S, C> @NotNull ExchangeChangeResultDto run(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeChangeSetAdapter<S, C> adapter,
      @NotNull S changeSet,
      boolean guarded) {
    boolean dryRun = adapter.dryRun(changeSet);
    List<Planned<C>> plan = adapter.plan(caller, changeSet);
    if (guarded && !dryRun && adapter.holdsBack(caller, changeSet, plan)) {
      count(caller, adapter.resource(), ExchangeOps.HELD);
      throw ExchangeProblemException.massChangeConfirmationRequired();
    }
    Execution<C> execution = adapter.start(caller, UUID.randomUUID(), plan, dryRun);
    int applied = 0;
    int unchanged = 0;
    List<ExchangeChangeResultDto.OpResult> results = new ArrayList<>();
    for (int i = 0; i < plan.size(); i++) {
      Outcome outcome =
          switch (plan.get(i)) {
            case Skip<C> skip -> skip.outcome();
            case Write<C> write -> execution.run(write.change());
          };
      if (!dryRun) {
        count(caller, adapter.resource(), outcome.result());
      }
      if (ExchangeOps.APPLIED.equals(outcome.result())) {
        applied++;
        continue;
      }
      if (ExchangeOps.UNCHANGED.equals(outcome.result())) {
        unchanged++;
      }
      results.add(
          new ExchangeChangeResultDto.OpResult(
              i, adapter.opId(changeSet, i), outcome.result(), outcome.reason()));
    }
    if (!dryRun && applied > 0) {
      execution.announce();
    }
    ExchangeChangeSetAdapter.Effects effects = execution.effects();
    return new ExchangeChangeResultDto(
        dryRun,
        applied,
        unchanged,
        plan.size() - applied - unchanged,
        List.copyOf(results),
        effects.offersReduced(),
        effects.offersRemoved(),
        effects.detachedFromMissions());
  }

  /**
   * Counts one outcome on the write counter of the caller's client and the resource.
   *
   * @param caller the caller, whose client the relay bounded by the registry
   * @param resource the resource
   * @param outcome the outcome
   */
  private void count(
      @NotNull ExchangeCaller caller, @NotNull ExchangeResource resource, @NotNull String outcome) {
    meterRegistry
        .counter(
            MetricNames.EXCHANGE_WRITES,
            MetricNames.TAG_CLIENT_ID,
            caller.clientId(),
            MetricNames.TAG_RESOURCE,
            resource.name().toLowerCase(Locale.ROOT),
            MetricNames.TAG_OUTCOME,
            outcome)
        .increment();
  }
}
