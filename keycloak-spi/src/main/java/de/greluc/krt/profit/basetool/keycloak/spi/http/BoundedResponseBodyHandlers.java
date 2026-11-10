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

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/** Response handlers that cancel upstream reads before retaining more than their byte budget. */
public final class BoundedResponseBodyHandlers {
  private BoundedResponseBodyHandlers() {}

  /**
   * Decodes at most {@code maximumBytes} bytes as UTF-8 and fails larger responses.
   *
   * @param maximumBytes positive retained-response budget
   * @return a handler that cancels the upstream subscription on overflow
   */
  public static HttpResponse.@NotNull BodyHandler<String> utf8(int maximumBytes) {
    if (maximumBytes <= 0) {
      throw new IllegalArgumentException("Response byte budget must be positive");
    }
    return info -> new LimitedSubscriber(maximumBytes);
  }

  /** Counts bytes before handing each received buffer group to the string decoder. */
  @RequiredArgsConstructor
  private static final class LimitedSubscriber implements HttpResponse.BodySubscriber<String> {
    private final int maximumBytes;
    private final HttpResponse.BodySubscriber<String> delegate =
        HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
    private Flow.Subscription subscription;
    private int received;
    private boolean failed;

    @Override
    public CompletionStage<String> getBody() {
      return delegate.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription upstream) {
      subscription = upstream;
      delegate.onSubscribe(upstream);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      if (failed) {
        return;
      }
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > maximumBytes - received) {
          failed = true;
          subscription.cancel();
          delegate.onError(new IOException("HTTP response exceeds its byte budget"));
          return;
        }
        received += buffer.remaining();
      }
      delegate.onNext(buffers);
    }

    @Override
    public void onError(Throwable error) {
      if (!failed) {
        delegate.onError(error);
      }
    }

    @Override
    public void onComplete() {
      if (!failed) {
        delegate.onComplete();
      }
    }
  }
}
