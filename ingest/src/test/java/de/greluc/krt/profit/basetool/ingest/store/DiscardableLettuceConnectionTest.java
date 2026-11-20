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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.ingest.store.DiscardableLettuceConnection.TrackingConnectionProvider;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultStringRedisConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * A discardable connection closes exactly the native connections it borrowed and has not released,
 * and is found behind the decoration a {@code StringRedisTemplate} adds (ADR-0389).
 */
class DiscardableLettuceConnectionTest {

  private static final long TIMEOUT_MILLIS = 2_000L;

  private final LettuceConnectionProvider pool = mock(LettuceConnectionProvider.class);

  private final StatefulRedisConnection<?, ?> dedicated = mock(StatefulRedisConnection.class);

  private final TrackingConnectionProvider provider = new TrackingConnectionProvider(pool);

  @Test
  void discardClosesEveryConnectionLentAndNotReleased() {
    StatefulRedisPubSubConnection<?, ?> pubSub = mock(StatefulRedisPubSubConnection.class);
    when(pool.getConnection(StatefulRedisConnection.class)).thenReturn(dedicated);
    when(pool.getConnectionAsync(StatefulRedisPubSubConnection.class))
        .thenReturn(CompletableFuture.completedFuture(pubSub));
    DiscardableLettuceConnection connection = connection();

    assertThat(provider.getConnection(StatefulRedisConnection.class)).isSameAs(dedicated);
    assertThat(
            provider.getConnectionAsync(StatefulRedisPubSubConnection.class).toCompletableFuture())
        .isCompletedWithValue(pubSub);
    connection.discard();

    verify(dedicated).close();
    verify(pubSub).close();
  }

  @Test
  void aReleasedConnectionGoesBackToThePoolAndIsNotClosedLater() {
    when(pool.getConnection(StatefulRedisConnection.class)).thenReturn(dedicated);

    provider.release(provider.getConnection(StatefulRedisConnection.class));
    connection().discard();

    verify(pool).release(dedicated);
    verify(dedicated, never()).close();
  }

  @Test
  void anAsynchronouslyReleasedConnectionGoesBackToThePoolAndIsNotClosedLater() {
    CompletableFuture<Void> released = CompletableFuture.completedFuture(null);
    when(pool.getConnection(StatefulRedisConnection.class)).thenReturn(dedicated);
    when(pool.releaseAsync(dedicated)).thenReturn(released);

    assertThat(provider.releaseAsync(provider.getConnection(StatefulRedisConnection.class)))
        .isSameAs(released);
    connection().discard();

    verify(dedicated, never()).close();
  }

  @Test
  void theConnectionIsFoundBehindTheStringTemplatesDecoration() {
    DiscardableLettuceConnection connection = connection();

    assertThat(DiscardableConnection.behind(new DefaultStringRedisConnection(connection)))
        .isSameAs(connection);
    assertThat(DiscardableConnection.behind(connection)).isSameAs(connection);
  }

  @Test
  void discardWorksThroughTheConnectionProxyATemplateHandsToItsCallbacks() {
    when(pool.getConnection(StatefulRedisConnection.class)).thenReturn(dedicated);
    provider.getConnection(StatefulRedisConnection.class);
    ProxyingTemplate template = new ProxyingTemplate();

    DiscardableConnection.behind(template.proxy(connection())).discard();
    DiscardableConnection.behind(
            template.proxy(new DefaultStringRedisConnection(template.proxy(connection()))))
        .discard();

    verify(dedicated, times(2)).close();
  }

  @Test
  void aConnectionFromAnotherFactoryIsRefused() {
    RedisConnection foreign = mock(RedisConnection.class);

    assertThatThrownBy(() -> DiscardableConnection.behind(foreign))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("RedisClientConfig");
  }

  /**
   * Creates a connection over the tracking provider, without touching Redis.
   *
   * @return the connection
   */
  private DiscardableLettuceConnection connection() {
    return new DiscardableLettuceConnection(null, provider, TIMEOUT_MILLIS, 0);
  }

  /** Exposes the JDK proxy a {@code RedisTemplate} wraps a connection in before a callback. */
  private static final class ProxyingTemplate extends StringRedisTemplate {

    /**
     * Wraps a connection as the template does for a callback.
     *
     * @param connection the connection
     * @return the close-suppressing proxy
     */
    RedisConnection proxy(RedisConnection connection) {
      return createRedisConnectionProxy(connection);
    }
  }
}
