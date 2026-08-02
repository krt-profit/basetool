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

package de.greluc.krt.profit.basetool.backend.support;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit tests for {@link OptimisticLock}, the version-check helper family (S2, #908). One nested
 * class per null/equality shape pins the exact skip-vs-409 semantics the migrated call sites relied
 * on, plus the entity-type + identifier carried on the thrown 409.
 */
class OptimisticLockTest {

  private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000ab");

  /**
   * {@link OptimisticLock#check(Long, Long, Class, Object)} — persisted-null-safe, client required.
   */
  @Nested
  class Check {

    @Test
    void passesWhenVersionsMatch() {
      assertDoesNotThrow(() -> OptimisticLock.check(3L, 3L, String.class, ID));
    }

    @Test
    void throwsWhenVersionsDiffer() {
      assertThrows(
          ObjectOptimisticLockingFailureException.class,
          () -> OptimisticLock.check(4L, 3L, String.class, ID));
    }

    @Test
    void skipsWhenPersistedVersionIsNull() {
      assertDoesNotThrow(() -> OptimisticLock.check(null, 3L, String.class, ID));
    }

    @Test
    void throwsWhenClientVersionIsNull() {
      // A null client version is a mismatch here (an omitted version 409s) — unlike
      // checkOptionalClient.
      assertThrows(
          ObjectOptimisticLockingFailureException.class,
          () -> OptimisticLock.check(3L, null, String.class, ID));
    }

    @Test
    void thrown409CarriesEntityTypeAndIdentifier() {
      ObjectOptimisticLockingFailureException ex =
          assertThrows(
              ObjectOptimisticLockingFailureException.class,
              () -> OptimisticLock.check(4L, 3L, String.class, ID));
      assertEquals(ID, ex.getIdentifier(), "identifier is echoed onto the 409");
      assertTrue(
          ex.getPersistentClassName().contains("String"),
          "the 409 names the entity type: " + ex.getPersistentClassName());
    }
  }

  /**
   * A primitive-{@code long} client version autoboxes to {@code Long} and flows through {@link
   * OptimisticLock#check(Long, Long, Class, Object)} with byte-identical results — the path the
   * former primitive-{@code !=} call sites (e.g. the org-unit bank access helper) now take.
   */
  @Nested
  class PrimitiveClientVersion {

    @Test
    void matchingPrimitiveClientPasses() {
      long client = 7L;
      assertDoesNotThrow(() -> OptimisticLock.check(7L, client, String.class, ID));
    }

    @Test
    void differingPrimitiveClientThrows() {
      long client = 7L;
      assertThrows(
          ObjectOptimisticLockingFailureException.class,
          () -> OptimisticLock.check(8L, client, String.class, ID));
    }
  }

  /**
   * {@link OptimisticLock#checkOptionalClient(Long, Long, Class, Object)} — admin
   * skip-on-null-client.
   */
  @Nested
  class CheckOptionalClient {

    @Test
    void passesWhenVersionsMatch() {
      assertDoesNotThrow(() -> OptimisticLock.checkOptionalClient(3L, 3L, String.class, ID));
    }

    @Test
    void throwsWhenBothPresentAndDiffer() {
      assertThrows(
          ObjectOptimisticLockingFailureException.class,
          () -> OptimisticLock.checkOptionalClient(4L, 3L, String.class, ID));
    }

    @Test
    void skipsWhenClientVersionIsNull() {
      // The distinguishing behaviour vs check(): an omitted client version passes (force-save).
      assertDoesNotThrow(() -> OptimisticLock.checkOptionalClient(4L, null, String.class, ID));
    }

    @Test
    void skipsWhenPersistedVersionIsNull() {
      assertDoesNotThrow(() -> OptimisticLock.checkOptionalClient(null, 3L, String.class, ID));
    }
  }

  /**
   * {@link OptimisticLock#checkRequired(Long, long, Class, Object)} — unversioned entity is a
   * conflict.
   */
  @Nested
  class CheckRequired {

    @Test
    void passesWhenVersionsMatch() {
      assertDoesNotThrow(() -> OptimisticLock.checkRequired(5L, 5L, String.class, ID));
    }

    @Test
    void throwsWhenVersionsDiffer() {
      assertThrows(
          ObjectOptimisticLockingFailureException.class,
          () -> OptimisticLock.checkRequired(6L, 5L, String.class, ID));
    }

    @Test
    void throwsWhenPersistedVersionIsNull() {
      // Unlike check(), an absent persisted version is itself a conflict here.
      Long persisted = null;
      assertThrows(
          ObjectOptimisticLockingFailureException.class,
          () -> OptimisticLock.checkRequired(persisted, 5L, String.class, ID));
    }
  }

  /**
   * The identifier may be {@code null} or a composite-key object — it is only used for the message.
   */
  @Test
  void nullIdentifierIsAccepted() {
    ObjectOptimisticLockingFailureException ex =
        assertThrows(
            ObjectOptimisticLockingFailureException.class,
            () -> OptimisticLock.check(2L, 1L, String.class, null));
    assertTrue(ex.getPersistentClassName().contains("String"), ex.getPersistentClassName());
  }

  /**
   * The compared version pair travels on the exception message. It is the only field that separates
   * genuine contention from the REQ-FE-003 failure mode where a fragment never re-echoes the bumped
   * version, so its exact shape is the contract {@code GlobalExceptionHandler} logs.
   */
  @Nested
  class VersionPairMessage {

    @Test
    void checkReportsClientAsExpectedAndPersistedAsPersisted() {
      ObjectOptimisticLockingFailureException ex =
          assertThrows(
              ObjectOptimisticLockingFailureException.class,
              () -> OptimisticLock.check(9L, 4L, String.class, ID));
      assertEquals("expected=4 persisted=9", ex.getMessage());
    }

    @Test
    void checkOptionalClientReportsTheSamePair() {
      ObjectOptimisticLockingFailureException ex =
          assertThrows(
              ObjectOptimisticLockingFailureException.class,
              () -> OptimisticLock.checkOptionalClient(9L, 4L, String.class, ID));
      assertEquals("expected=4 persisted=9", ex.getMessage());
    }

    @Test
    void checkRequiredReportsAnAbsentPersistedVersionAsNull() {
      Long persisted = null;
      ObjectOptimisticLockingFailureException ex =
          assertThrows(
              ObjectOptimisticLockingFailureException.class,
              () -> OptimisticLock.checkRequired(persisted, 5L, String.class, ID));
      assertEquals("expected=5 persisted=null", ex.getMessage());
    }

    @Test
    void omittedClientVersionRendersAsNullRatherThanBeingDropped() {
      // The "client sent no version at all" case must stay distinguishable from "client sent 0".
      ObjectOptimisticLockingFailureException ex =
          assertThrows(
              ObjectOptimisticLockingFailureException.class,
              () -> OptimisticLock.check(3L, null, String.class, ID));
      assertEquals("expected=null persisted=3", ex.getMessage());
    }

    @Test
    void entityTypeAndIdentifierSurviveAlongsideTheMessage() {
      // The message overload must not cost us the two fields the handler also logs.
      ObjectOptimisticLockingFailureException ex =
          assertThrows(
              ObjectOptimisticLockingFailureException.class,
              () -> OptimisticLock.check(9L, 4L, String.class, ID));
      assertEquals(ID, ex.getIdentifier());
      assertTrue(ex.getPersistentClassName().contains("String"), ex.getPersistentClassName());
    }
  }
}
