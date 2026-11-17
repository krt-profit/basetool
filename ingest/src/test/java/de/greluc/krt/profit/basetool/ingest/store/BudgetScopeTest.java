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

package de.greluc.krt.profit.basetool.ingest.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The decisions of one budget scope (REQ-XCH-023, ADR-0389): what an entry is charged, whether it
 * fits the scope's limit, and what a step leaves of the scope — the same rules the budget applied
 * before it ran as an optimistic transaction.
 */
class BudgetScopeTest {

  private static final String RELEASE = "ingest:xch:idem-lock:a:m1:x|2512";

  private static final String ADD = "ingest:xch:idem:a:m1:x|1512";

  private static final long EXPIRY = 10_000L;

  @ParameterizedTest
  @CsvSource(
      delimiter = ';',
      value = {"ingest:xch:idem:k|512;512", "a|b|7;7", "'';0", "no-charge;0", "k|x;0", "k|-5;0"})
  void anEntryIsChargedTheNumberAfterItsLastBar(String entry, long charge) {
    assertThat(BudgetScope.chargeOf(entry)).isEqualTo(charge);
  }

  @Test
  void aNegativeLimitIsNotChecked() {
    assertThat(scope(10_000L, 1, false, null).admits("", ADD, -1L)).isTrue();
  }

  @Test
  void anEntryFitsUpToTheLimitAndNotABytePast() {
    assertThat(scope(2584L, 1, false, null).admits("", ADD, 4096L)).isTrue();
    assertThat(scope(2585L, 1, false, null).admits("", ADD, 4096L)).isFalse();
  }

  @Test
  void aHeldReleaseFreesItsBytesForTheEntryThatReplacesIt() {
    assertThat(scope(3000L, 1, true, null).admits(RELEASE, ADD, 4096L)).isTrue();
    assertThat(scope(3000L, 1, false, null).admits(RELEASE, ADD, 4096L))
        .as("a release the scope no longer holds frees nothing")
        .isFalse();
  }

  @Test
  void aStepThatDoesNotApplyOnlyPrunes() {
    BudgetScope.Change change = scope(3000L, 2, true, null).change(false, RELEASE, ADD, EXPIRY);

    assertThat(change).isEqualTo(new BudgetScope.Change(3000L, false, false, false));
  }

  @Test
  void aPrunedScopeWithNothingLeftIsEmpty() {
    BudgetScope.Change change = scope(0L, 0, false, null).change(false, "", "", EXPIRY);

    assertThat(change).isEqualTo(new BudgetScope.Change(0L, true, false, false));
  }

  @Test
  void aSettleReplacesTheReservationWithTheValue() {
    BudgetScope.Change change = scope(2512L, 1, true, null).change(true, RELEASE, ADD, EXPIRY);

    assertThat(change).isEqualTo(new BudgetScope.Change(1512L, false, true, true));
  }

  @Test
  void anEntryHeldWithAnEarlierExpiryIsRaisedAndCountedOnce() {
    BudgetScope.Change change = scope(1512L, 1, false, 5_000d).change(true, "", ADD, EXPIRY);

    assertThat(change).isEqualTo(new BudgetScope.Change(1512L, false, false, true));
  }

  @Test
  void anEntryHeldWithALaterExpiryIsLeftAlone() {
    BudgetScope.Change change = scope(1512L, 1, false, 20_000d).change(true, "", ADD, EXPIRY);

    assertThat(change).isEqualTo(new BudgetScope.Change(1512L, false, false, false));
  }

  @Test
  void anEntryReleasedAndAddedUnderTheSameNameIsAddedAgain() {
    BudgetScope.Change change = scope(1512L, 1, true, 5_000d).change(true, ADD, ADD, EXPIRY);

    assertThat(change).isEqualTo(new BudgetScope.Change(1512L, false, true, true));
  }

  @Test
  void releasingTheLastEntryEmptiesTheScope() {
    BudgetScope.Change change = scope(2512L, 1, true, null).change(true, RELEASE, "", EXPIRY);

    assertThat(change).isEqualTo(new BudgetScope.Change(0L, true, true, false));
  }

  @Test
  void aReleaseNeverTakesTheTotalBelowZero() {
    BudgetScope.Change change = scope(100L, 2, true, null).change(true, RELEASE, "", EXPIRY);

    assertThat(change.used()).isZero();
    assertThat(change.empty()).isFalse();
  }

  /**
   * Builds a scope as a step would have read it.
   *
   * @param used the bytes counted after pruning
   * @param remaining the entries held after pruning
   * @param releaseHeld whether the release is still held
   * @param addExpiry the expiry the added entry is held with, or {@code null}
   * @return the scope
   */
  private static BudgetScope scope(
      long used, long remaining, boolean releaseHeld, @Nullable Double addExpiry) {
    return new BudgetScope(
        "ingest:xch:budget:m:a:m1",
        "ingest:xch:budget-sum:m:a:m1",
        used,
        Set.of(),
        remaining,
        releaseHeld,
        addExpiry);
  }
}
