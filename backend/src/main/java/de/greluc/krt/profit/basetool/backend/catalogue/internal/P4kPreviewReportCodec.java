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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Stores a preview's change report on its job as gzipped JSON (REQ-P4K-015): a LIVE catalogue's
 * report holds tens of thousands of {@code UNMATCHED} rows, which compress well.
 */
@Component
@RequiredArgsConstructor
public class P4kPreviewReportCodec {

  /** The stored shape: the report rows in the order found. */
  private static final TypeReference<List<P4kImportChangeDto>> ROWS = new TypeReference<>() {};

  private final ObjectMapper objectMapper;

  /**
   * Encodes report rows.
   *
   * @param rows the rows in the order found
   * @return gzipped JSON
   */
  public byte @NotNull [] encode(@NotNull List<P4kImportChangeDto> rows) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (OutputStream gzip = new GZIPOutputStream(bytes)) {
      objectMapper.writeValue(gzip, rows);
    } catch (IOException e) {
      throw new UncheckedIOException("Compressing a preview report in memory failed.", e);
    }
    return bytes.toByteArray();
  }

  /**
   * Decodes stored report rows.
   *
   * @param stored the gzipped JSON
   * @return the rows in the order found
   */
  @NotNull
  @Unmodifiable
  public List<P4kImportChangeDto> decode(byte @NotNull [] stored) {
    try (InputStream gzip = new GZIPInputStream(new ByteArrayInputStream(stored))) {
      return List.copyOf(objectMapper.readValue(gzip, ROWS));
    } catch (IOException e) {
      throw new UncheckedIOException("A stored preview report is not readable gzip.", e);
    }
  }
}
