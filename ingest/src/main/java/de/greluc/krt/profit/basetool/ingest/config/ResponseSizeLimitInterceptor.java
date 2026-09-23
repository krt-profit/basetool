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

package de.greluc.krt.profit.basetool.ingest.config;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Caps how many response-body bytes a {@code RestClient} will read from one exchange.
 *
 * <p>The reactive client this replaces buffered each backend response in memory and refused one
 * past its {@code maxInMemorySize}, set to {@code app.ingest.max-payload-bytes}. {@code RestClient}
 * streams the body into the message converter and has no such ceiling of its own, so without this
 * interceptor a hostile or buggy backend response could make the relay allocate without bound. The
 * contract is kept: reading past the cap throws an {@link IOException}, which {@code RestClient}
 * surfaces as a {@code RestClientException} — a failed relay, never a silently truncated draft.
 *
 * <p>The backend module carries an identical copy for its UEX and SC-Wiki clients; the two modules
 * share no runtime code.
 */
@RequiredArgsConstructor
public final class ResponseSizeLimitInterceptor implements ClientHttpRequestInterceptor {

  /** The largest number of body bytes one response may deliver; must be positive. */
  private final long maxBytes;

  /**
   * Executes the request and hands back a response whose body stream fails once more than {@link
   * #maxBytes} bytes have been read from it.
   *
   * @param request the outbound request
   * @param body the buffered request body
   * @param execution the rest of the interceptor chain
   * @return the response, with a size-capped body stream
   * @throws IOException when the exchange itself fails
   */
  @Override
  public @NotNull ClientHttpResponse intercept(
      @NotNull HttpRequest request,
      byte @NotNull [] body,
      @NotNull ClientHttpRequestExecution execution)
      throws IOException {
    return new CappedResponse(execution.execute(request, body), maxBytes);
  }

  /**
   * A response that delegates everything to the real one except {@link #getBody()}, which is
   * wrapped in a {@link CappedInputStream} created once and reused, so repeated calls observe one
   * shared byte count.
   */
  private static final class CappedResponse implements ClientHttpResponse {

    /** The response as the JDK request factory produced it. */
    private final ClientHttpResponse delegate;

    /** The cap handed to the body stream. */
    private final long maxBytes;

    /** The capped body stream, created on the first {@link #getBody()} call. */
    private InputStream cappedBody;

    /**
     * Wraps a response.
     *
     * @param delegate the real response
     * @param maxBytes the body cap
     */
    CappedResponse(@NotNull ClientHttpResponse delegate, long maxBytes) {
      this.delegate = delegate;
      this.maxBytes = maxBytes;
    }

    /**
     * Returns the delegate's status code unchanged.
     *
     * @return the response status
     * @throws IOException when the status cannot be read
     */
    @Override
    public @NotNull HttpStatusCode getStatusCode() throws IOException {
      return delegate.getStatusCode();
    }

    /**
     * Returns the delegate's reason phrase unchanged.
     *
     * @return the status text
     * @throws IOException when the status cannot be read
     */
    @Override
    public @NotNull String getStatusText() throws IOException {
      return delegate.getStatusText();
    }

    /**
     * Returns the delegate's headers unchanged.
     *
     * @return the response headers
     */
    @Override
    public @NotNull HttpHeaders getHeaders() {
      return delegate.getHeaders();
    }

    /**
     * Returns the delegate's body behind a stream that throws once more than the cap has been read.
     *
     * @return the capped body stream; the same instance on every call
     * @throws IOException when the delegate's body cannot be opened
     */
    @Override
    public synchronized @NotNull InputStream getBody() throws IOException {
      if (cappedBody == null) {
        cappedBody = new CappedInputStream(delegate.getBody(), maxBytes);
      }
      return cappedBody;
    }

    /** Closes the delegate response, which releases its connection. */
    @Override
    public void close() {
      delegate.close();
    }
  }

  /**
   * An input stream that counts what is read through it and throws an {@link IOException} as soon
   * as the count exceeds the cap. {@code skip} is routed through {@code read} so skipped bytes
   * count too.
   */
  private static final class CappedInputStream extends FilterInputStream {

    /** The cap in bytes. */
    private final long maxBytes;

    /** Bytes delivered so far. */
    private long count;

    /**
     * Wraps a stream.
     *
     * @param in the stream to cap
     * @param maxBytes the largest number of bytes it may deliver
     */
    CappedInputStream(@NotNull InputStream in, long maxBytes) {
      super(in);
      this.maxBytes = maxBytes;
    }

    /**
     * Reads one byte and counts it.
     *
     * @return the byte, or {@code -1} at the end of the stream
     * @throws IOException when the cap is exceeded or the underlying read fails
     */
    @Override
    public int read() throws IOException {
      int b = super.read();
      if (b >= 0) {
        record(1);
      }
      return b;
    }

    /**
     * Reads into a buffer and counts what arrived.
     *
     * @param b the destination buffer
     * @param off the offset in {@code b}
     * @param len the maximum number of bytes to read
     * @return the number of bytes read, or {@code -1} at the end of the stream
     * @throws IOException when the cap is exceeded or the underlying read fails
     */
    @Override
    public int read(byte @NotNull [] b, int off, int len) throws IOException {
      int n = super.read(b, off, len);
      if (n > 0) {
        record(n);
      }
      return n;
    }

    /**
     * Reports that mark/reset is unsupported, so a caller that wants to peek (Spring's empty-body
     * check) wraps the stream in a pushback buffer instead of re-reading bytes this stream has
     * already counted.
     *
     * @return always {@code false}
     */
    @Override
    public boolean markSupported() {
      return false;
    }

    /**
     * Skips by reading, so skipped bytes are counted against the cap as well.
     *
     * @param n the number of bytes to skip
     * @return the number of bytes actually skipped
     * @throws IOException when the cap is exceeded or the underlying read fails
     */
    @Override
    public long skip(long n) throws IOException {
      byte[] scratch = new byte[(int) Math.min(8192, Math.max(0, n))];
      long skipped = 0;
      while (skipped < n) {
        int r = read(scratch, 0, (int) Math.min(scratch.length, n - skipped));
        if (r < 0) {
          break;
        }
        skipped += r;
      }
      return skipped;
    }

    /**
     * Adds to the byte count and fails once it passes the cap.
     *
     * @param n the number of bytes just read
     * @throws IOException when the count exceeds the cap
     */
    private void record(int n) throws IOException {
      count += n;
      if (count > maxBytes) {
        throw new IOException("Response body exceeds the limit of " + maxBytes + " bytes");
      }
    }
  }
}
