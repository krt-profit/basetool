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

package de.greluc.krt.profit.basetool.keycloak.spi.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

/** Exercises response limits independently of transport buffer boundaries. */
class BoundedResponseBodyHandlersTest {
  @Test
  void acceptsExactBudgetAndUtf8() {
    var subscriber = BoundedResponseBodyHandlers.utf8(4).apply(null);
    var subscription = new Subscription();
    subscriber.onSubscribe(subscription);
    subscriber.onNext(List.of(ByteBuffer.wrap("test".getBytes(StandardCharsets.UTF_8))));
    subscriber.onComplete();
    assertEquals("test", subscriber.getBody().toCompletableFuture().join());
    assertFalse(subscription.cancelled);
  }

  @Test
  void rejectsOneOversizedChunkAndCancelsImmediately() {
    var subscriber = BoundedResponseBodyHandlers.utf8(4).apply(null);
    var subscription = new Subscription();
    subscriber.onSubscribe(subscription);
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[5])));
    assertTrue(subscription.cancelled);
    assertThrows(
        CompletionException.class, () -> subscriber.getBody().toCompletableFuture().join());
  }

  @Test
  void rejectsChunkedOverflowWithoutRetainingLaterBuffers() {
    var subscriber = BoundedResponseBodyHandlers.utf8(4).apply(null);
    var subscription = new Subscription();
    subscriber.onSubscribe(subscription);
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[3])));
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[2])));
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[100])));
    subscriber.onComplete();
    assertTrue(subscription.cancelled);
    assertThrows(
        CompletionException.class, () -> subscriber.getBody().toCompletableFuture().join());
  }

  @Test
  void refusesInvalidBudget() {
    assertThrows(IllegalArgumentException.class, () -> BoundedResponseBodyHandlers.utf8(0));
  }

  /** Records cancellation without performing I/O. */
  private static final class Subscription implements Flow.Subscription {
    private boolean cancelled;

    @Override
    public void request(long count) {}

    @Override
    public void cancel() {
      cancelled = true;
    }
  }
}
