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

package de.greluc.krt.profit.basetool.ingest.edge;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.jetbrains.annotations.NotNull;

/**
 * Re-serves an already-read request body from memory so the controller can read it; the buffer is
 * adopted without copying.
 */
public final class CachedBodyRequest extends HttpServletRequestWrapper {

  /** The measured body; owned exclusively by this wrapper and never mutated. */
  private final byte[] body;

  /**
   * Wraps the request around its already-read body.
   *
   * @param request the original request whose stream was consumed
   * @param body the body bytes, adopted without copying; the caller must not retain or mutate them
   */
  public CachedBodyRequest(@NotNull HttpServletRequest request, byte @NotNull [] body) {
    super(request);
    this.body = body;
  }

  @NotNull
  @Override
  public ServletInputStream getInputStream() {
    ByteArrayInputStream delegate = new ByteArrayInputStream(body);
    return new ServletInputStream() {
      @Override
      public int read() {
        return delegate.read();
      }

      @Override
      public int read(byte @NotNull [] buffer, int offset, int length) {
        return delegate.read(buffer, offset, length);
      }

      @Override
      public boolean isFinished() {
        return delegate.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener readListener) {
        throw new UnsupportedOperationException("Async reads are not supported for ingest bodies");
      }
    };
  }

  @NotNull
  @Override
  public BufferedReader getReader() {
    return new BufferedReader(
        new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
  }
}
