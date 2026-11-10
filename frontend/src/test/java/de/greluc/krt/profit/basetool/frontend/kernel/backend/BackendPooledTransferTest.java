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

package de.greluc.krt.profit.basetool.frontend.kernel.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.PooledByteBufAllocator;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Pins pooled buffer ownership on complete, failed and cancelled servlet transfers. */
class BackendPooledTransferTest {

  private static final Duration RELEASE_WAIT = Duration.ofSeconds(2);
  private static final NettyDataBufferFactory BUFFERS =
      new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);

  @Test
  void successfulOutputReleasesEveryPooledBuffer() throws Exception {
    List<NettyDataBuffer> buffers = List.of(buffer(), buffer(), buffer());

    client(Flux.<DataBuffer>fromIterable(buffers), Duration.ofSeconds(10))
        .download("/export", web -> web.get().uri("/export"))
        .writeTo(OutputStream.nullOutputStream(), _ -> {});

    assertReleased(buffers);
  }

  @Test
  void aDisconnectedOutputReleasesCurrentQueuedAndUnrequestedPooledBuffers() throws Exception {
    List<NettyDataBuffer> buffers = List.of(buffer(), buffer(), buffer());
    BackendBinaryTransfer transfer =
        client(Flux.<DataBuffer>fromIterable(buffers), Duration.ofSeconds(10))
            .download("/export", web -> web.get().uri("/export"));

    assertThatThrownBy(() ->
            transfer.writeTo(
                new OutputStream() {
                  /** Models a browser connection that no longer accepts bytes. */
                  @Override
                  public void write(int value) throws IOException {
                    throw new IOException("Synthetic disconnected browser");
                  }
                },
                _ -> {}))
        .isInstanceOf(BackendServiceException.class);

    assertReleased(buffers);
  }

  @Test
  void timeoutCancellationReleasesAPooledBufferWaitingUpstream() throws Exception {
    List<NettyDataBuffer> buffers = List.of(buffer(), buffer());
    Flux<DataBuffer> body =
        Flux.concat(
            Mono.just(buffers.getFirst()),
            Mono.just(buffers.getLast()).delayElement(Duration.ofSeconds(5)));
    BackendBinaryTransfer transfer =
        client(body, Duration.ofMillis(100)).download("/export", web -> web.get().uri("/export"));

    assertThatThrownBy(() -> transfer.writeTo(OutputStream.nullOutputStream(), _ -> {}))
        .isInstanceOfSatisfying(
            BackendServiceException.class,
            failure -> assertThat(failure.getStatusCode()).isEqualTo(504));

    assertReleased(buffers);
  }

  private static NettyDataBuffer buffer() {
    NettyDataBuffer buffer = BUFFERS.allocateBuffer(128);
    buffer.write(new byte[128]);
    return buffer;
  }

  private static BackendApiClient client(Flux<DataBuffer> body, Duration budget) {
    AtomicBoolean subscribed = new AtomicBoolean();
    Flux<DataBuffer> networkBody =
        Flux.defer(() -> subscribed.compareAndSet(false, true) ? body : Flux.empty());
    WebClient transport =
        WebClient.builder()
            .exchangeFunction(
                _ -> Mono.just(ClientResponse.create(HttpStatus.OK).body(networkBody).build()))
            .build();
    return new BackendApiClient(
        transport,
        transport,
        new SimpleMeterRegistry(),
        new NoOpCacheManager(),
        BackendCallPolicyFixture.create(budget));
  }

  private static void assertReleased(List<NettyDataBuffer> buffers) throws InterruptedException {
    long deadline = System.nanoTime() + RELEASE_WAIT.toNanos();
    while (buffers.stream().anyMatch(buffer -> buffer.getNativeBuffer().refCnt() != 0)
        && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertThat(buffers)
        .allSatisfy(buffer -> assertThat(buffer.getNativeBuffer().refCnt()).isZero());
  }
}
