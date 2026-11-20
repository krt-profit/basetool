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

import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionProvider;

/**
 * A {@link LettuceConnection} that borrows its native connections through a {@link
 * TrackingConnectionProvider}, so {@link #discard()} can close them (ADR-0389).
 */
final class DiscardableLettuceConnection extends LettuceConnection
    implements DiscardableConnection {

  /** The provider this connection borrows its native connections through. */
  private final TrackingConnectionProvider borrowed;

  /**
   * Creates the connection.
   *
   * @param sharedConnection the shared native connection, {@code null} when none is shared
   * @param borrowed the provider the native connections are borrowed through
   * @param timeout the command timeout in milliseconds
   * @param database the database index
   */
  DiscardableLettuceConnection(
      @Nullable StatefulRedisConnection<byte[], byte[]> sharedConnection,
      @NotNull TrackingConnectionProvider borrowed,
      long timeout,
      int database) {
    super(sharedConnection, borrowed, timeout, database);
    this.borrowed = borrowed;
  }

  /** Closes every native connection this connection borrowed and has not released yet. */
  @Override
  public void discard() {
    borrowed.closeLent();
  }

  /**
   * A {@link LettuceConnectionProvider} that remembers the native connections it lent until they
   * come back.
   */
  @RequiredArgsConstructor
  static final class TrackingConnectionProvider implements LettuceConnectionProvider {

    /** The provider the native connections come from and go back to. */
    private final @NotNull LettuceConnectionProvider delegate;

    /** The native connections lent and not yet released. */
    private final Set<StatefulConnection<?, ?>> lent = ConcurrentHashMap.newKeySet();

    /**
     * Borrows a native connection from the delegate and remembers it.
     *
     * @param connectionType the connection type
     * @param <T> the connection type
     * @return the borrowed connection
     */
    @Override
    public <T extends StatefulConnection<?, ?>> T getConnection(Class<T> connectionType) {
      T connection = delegate.getConnection(connectionType);
      lent.add(connection);
      return connection;
    }

    /**
     * Borrows a native connection from the delegate and remembers it once it arrives.
     *
     * @param connectionType the connection type
     * @param <T> the connection type
     * @return the stage that completes with the borrowed connection
     */
    @Override
    public <T extends StatefulConnection<?, ?>> CompletionStage<T> getConnectionAsync(
        Class<T> connectionType) {
      return delegate
          .getConnectionAsync(connectionType)
          .thenApply(connection -> {
            lent.add(connection);
            return connection;
          });
    }

    /**
     * Forgets a native connection and gives it back to the delegate.
     *
     * @param connection the connection
     */
    @Override
    public void release(StatefulConnection<?, ?> connection) {
      lent.remove(connection);
      delegate.release(connection);
    }

    /**
     * Forgets a native connection and gives it back to the delegate.
     *
     * @param connection the connection
     * @return the delegate's release
     */
    @Override
    public CompletableFuture<Void> releaseAsync(StatefulConnection<?, ?> connection) {
      lent.remove(connection);
      return delegate.releaseAsync(connection);
    }

    /** Closes every native connection lent and not yet released; each is still released later. */
    void closeLent() {
      lent.forEach(StatefulConnection::close);
    }
  }
}
