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

    assertThat(change).isEqualTo(new BudgetScope.Change(3000L, false, false, false, false));
  }

  @Test
  void aPrunedScopeWithNothingLeftIsEmpty() {
    BudgetScope.Change change = scope(0L, 0, false, null).change(false, "", "", EXPIRY);

    assertThat(change).isEqualTo(new BudgetScope.Change(0L, true, false, false, true));
  }

  @Test
  void aSettleReplacesTheReservationWithTheValue() {
    BudgetScope.Change change = scope(2512L, 1, true, null).change(true, RELEASE, ADD, EXPIRY);

    assertThat(change).isEqualTo(new BudgetScope.Change(1512L, false, true, true, true));
  }

  @Test
  void anEntryHeldWithAnEarlierExpiryIsRaisedAndCountedOnce() {
    BudgetScope.Change change = scope(1512L, 1, false, 5_000d).change(true, "", ADD, EXPIRY);

    assertThat(change).isEqualTo(new BudgetScope.Change(1512L, false, false, true, true));
  }

  @Test
  void anEntryHeldWithALaterExpiryIsLeftAlone() {
    BudgetScope.Change change = scope(1512L, 1, false, 20_000d).change(true, "", ADD, EXPIRY);

    assertThat(change).isEqualTo(new BudgetScope.Change(1512L, false, false, false, false));
  }

  @Test
  void anEntryReleasedAndAddedUnderTheSameNameIsAddedAgain() {
    BudgetScope.Change change = scope(1512L, 1, true, 5_000d).change(true, ADD, ADD, EXPIRY);

    assertThat(change).isEqualTo(new BudgetScope.Change(1512L, false, true, true, true));
  }

  @Test
  void releasingTheLastEntryEmptiesTheScope() {
    BudgetScope.Change change = scope(2512L, 1, true, null).change(true, RELEASE, "", EXPIRY);

    assertThat(change).isEqualTo(new BudgetScope.Change(0L, true, true, false, true));
  }

  @Test
  void aReleaseNeverTakesTheTotalBelowZero() {
    BudgetScope.Change change = scope(100L, 2, true, null).change(true, RELEASE, "", EXPIRY);

    assertThat(change.used()).isZero();
    assertThat(change.empty()).isFalse();
  }

  @Test
  void aStepThatChangesNothingWritesNothing() {
    assertThat(scope(1512L, 1, true, null).change(false, RELEASE, ADD, EXPIRY).alters()).isFalse();
    assertThat(scope(1512L, 1, false, 20_000d).change(true, "", ADD, EXPIRY).alters()).isFalse();
  }

  @Test
  void aMissingOrDifferentStoredTotalIsWritten() {
    assertThat(scope(null, 1512L, 1, false, Set.of()).change(false, "", "", EXPIRY).alters())
        .as("a total rebuilt from the set is stored")
        .isTrue();
    assertThat(scope(999L, 1512L, 1, false, Set.of()).change(false, "", "", EXPIRY).alters())
        .as("a stored total that differs from the set is corrected")
        .isTrue();
  }

  @Test
  void anEmptyScopeWithoutATotalIsLeftAlone() {
    assertThat(scope(null, 0L, 0, false, Set.of()).change(false, "", "", EXPIRY).alters())
        .isFalse();
  }

  @Test
  void pruningAnExpiredEntryIsAWrite() {
    BudgetScope pruning = scope(1512L, 1512L, 1, false, Set.of("ingest:xch:idem:a:m1:old|512"));

    assertThat(pruning.change(false, "", "", EXPIRY).alters()).isTrue();
  }

  /**
   * Builds a scope as a step would have read it, its stored total equal to what it counts.
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
        used,
        Set.of(),
        remaining,
        releaseHeld,
        addExpiry);
  }

  /**
   * Builds a scope with a stored total of its own and the expired entries a step prunes.
   *
   * @param stored the running total as stored, or {@code null}
   * @param used the bytes counted after pruning
   * @param remaining the entries held after pruning
   * @param releaseHeld whether the release is still held
   * @param expired the expired entries
   * @return the scope
   */
  private static BudgetScope scope(
      @Nullable Long stored, long used, long remaining, boolean releaseHeld, Set<String> expired) {
    return new BudgetScope(
        "ingest:xch:budget:m:a:m1",
        "ingest:xch:budget-sum:m:a:m1",
        stored,
        used,
        expired,
        remaining,
        releaseHeld,
        null);
  }
}
