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

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What one exchange resource contributes to the {@link ExchangeChangeSetRunner}: how it plans a
 * change set, when the mass-change guard holds it back, and how it writes one planned change
 * (ADR-0285).
 *
 * @param <S> the resource's change set
 * @param <C> the resource's planned change
 */
interface ExchangeChangeSetAdapter<S, C> {

  /**
   * Returns the resource whose writes this adapter plans.
   *
   * @return the resource, which names the write counter's {@code resource} label
   */
  @NotNull
  ExchangeResource resource();

  /**
   * Whether a change set only asks what would happen.
   *
   * @param changeSet the change set
   * @return {@code true} for a dry run
   */
  boolean dryRun(@NotNull S changeSet);

  /**
   * Returns the client's id of one op.
   *
   * @param changeSet the change set
   * @param index the op's position
   * @return the op's id, or {@code null}
   */
  @Nullable
  String opId(@NotNull S changeSet, int index);

  /**
   * Decides every op of a change set in order, as if the earlier ops had run.
   *
   * @param caller the client, installation and member
   * @param changeSet the change set
   * @return one plan per op, in the change set's order
   */
  @NotNull
  List<Planned<C>> plan(@NotNull ExchangeCaller caller, @NotNull S changeSet);

  /**
   * Whether the planned change set removes so much that the member must confirm it (REQ-XCH-021).
   *
   * @param caller the client, installation and member
   * @param changeSet the change set
   * @param plan the plan
   * @return {@code true} when the mass-change guard holds it back
   */
  boolean holdsBack(
      @NotNull ExchangeCaller caller, @NotNull S changeSet, @NotNull List<Planned<C>> plan);

  /**
   * Prepares the writes of a planned change set, which the runner then hands over one by one.
   *
   * @param caller the client, installation and member
   * @param batch the change set's id in the journal
   * @param plan the plan
   * @param dryRun whether nothing is written
   * @return the execution
   */
  @NotNull
  Execution<C> start(
      @NotNull ExchangeCaller caller,
      @NotNull UUID batch,
      @NotNull List<Planned<C>> plan,
      boolean dryRun);

  /**
   * One planned op.
   *
   * @param <C> the resource's planned change
   */
  sealed interface Planned<C> permits Skip, Write {}

  /**
   * An op the plan already decided, which writes nothing.
   *
   * @param outcome its result and reason
   * @param <C> the resource's planned change
   */
  record Skip<C>(@NotNull Outcome outcome) implements Planned<C> {}

  /**
   * An op that hands a change to the execution.
   *
   * @param change the change
   * @param <C> the resource's planned change
   */
  record Write<C>(@NotNull C change) implements Planned<C> {}

  /**
   * Plans an op that writes nothing.
   *
   * @param result its result
   * @param reason its reason, or {@code null}
   * @param <C> the resource's planned change
   * @return the plan
   */
  static <C> @NotNull Planned<C> skip(@NotNull String result, @Nullable String reason) {
    return new Skip<>(new Outcome(result, reason));
  }

  /**
   * Plans an op that writes a change.
   *
   * @param change the change
   * @param <C> the resource's planned change
   * @return the plan
   */
  static <C> @NotNull Planned<C> write(@NotNull C change) {
    return new Write<>(change);
  }

  /**
   * The result of one op as the client sees it.
   *
   * @param result {@code applied}, {@code unchanged}, {@code unmatched}, {@code ambiguous} or
   *     {@code rejected}, which is also the write counter's {@code outcome} label
   * @param reason the error-registry code of a refusal, or {@code null}
   */
  record Outcome(@NotNull String result, @Nullable String reason) {

    /** The outcome of an op that changed something, or would have in a dry run. */
    static final Outcome APPLIED = new Outcome(ExchangeOps.APPLIED, null);

    /** The outcome of an op that found the state it asked for. */
    static final Outcome UNCHANGED = new Outcome(ExchangeOps.UNCHANGED, null);

    /**
     * The outcome of a refused op.
     *
     * @param reason the refusal's code
     * @return the outcome
     */
    static @NotNull Outcome rejected(@NotNull String reason) {
      return new Outcome(ExchangeOps.REJECTED, reason);
    }
  }

  /**
   * The resource-specific counts of a change set's result.
   *
   * @param offersReduced the Materialbörse offers lowered, or {@code null} when the resource has
   *     none
   * @param offersRemoved the Materialbörse offers removed, or {@code null} when the resource has
   *     none
   * @param detachedFromMissions the mission units removed ships left, or {@code null} when the
   *     resource has none
   */
  record Effects(
      @Nullable Integer offersReduced,
      @Nullable Integer offersRemoved,
      @Nullable Integer detachedFromMissions) {

    /** The effects of a resource that reports none. */
    static final Effects NONE = new Effects(null, null, null);
  }

  /**
   * The writes of one planned change set.
   *
   * @param <C> the resource's planned change
   */
  interface Execution<C> {

    /**
     * Writes one planned change, or in a dry run checks it only.
     *
     * @param change the change
     * @return the op's outcome; {@link Outcome#APPLIED} when it changed something or would have
     */
    @NotNull
    Outcome run(@NotNull C change);

    /**
     * Returns what the writes did beyond the ops' outcomes.
     *
     * @return the effects
     */
    @NotNull
    Effects effects();

    /** Tells the member's open pages about the writes, once the transaction commits. */
    void announce();
  }
}
