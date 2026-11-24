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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Computes the exchange fingerprint of a job order: the SHA-256 of a canonical encoding of its
 * member-independent read (REQ-OXCH-003, ADR-0406).
 *
 * <p>The encoding walks the read's record components in declaration order, so every component of
 * {@link JobOrderExchangeRead} is fingerprinted by construction. Each value carries a type tag, and
 * strings and lists their length, so no two different reads share an encoding; an amount is encoded
 * without its trailing zeros.
 */
final class JobOrderFingerprints {

  private static final byte NULL = 0;
  private static final byte FALSE = 1;
  private static final byte TRUE = 2;
  private static final byte INTEGER = 3;
  private static final byte LONG = 4;
  private static final byte DOUBLE = 5;
  private static final byte DECIMAL = 6;
  private static final byte TEXT = 7;
  private static final byte IDENTIFIER = 8;
  private static final byte INSTANT = 9;
  private static final byte CONSTANT = 10;
  private static final byte LIST = 11;
  private static final byte RECORD = 12;

  private JobOrderFingerprints() {}

  /**
   * Fingerprints one read.
   *
   * @param read the order's member-independent read
   * @return the 32-byte SHA-256 fingerprint
   * @throws IllegalStateException when the read holds a value type the encoding does not know
   */
  static byte @NotNull [] of(@NotNull JobOrderExchangeRead read) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      write(out, read);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return sha256().digest(bytes.toByteArray());
  }

  private static void write(DataOutputStream out, @Nullable Object value) throws IOException {
    switch (value) {
      case null -> out.writeByte(NULL);
      case Boolean flag -> out.writeByte(flag ? TRUE : FALSE);
      case Integer number -> {
        out.writeByte(INTEGER);
        out.writeInt(number);
      }
      case Long number -> {
        out.writeByte(LONG);
        out.writeLong(number);
      }
      case Double number -> {
        out.writeByte(DOUBLE);
        writeText(out, canonical(BigDecimal.valueOf(number)));
      }
      case BigDecimal number -> {
        out.writeByte(DECIMAL);
        writeText(out, canonical(number));
      }
      case String text -> {
        out.writeByte(TEXT);
        writeText(out, text);
      }
      case UUID id -> {
        out.writeByte(IDENTIFIER);
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
      }
      case Instant instant -> {
        out.writeByte(INSTANT);
        out.writeLong(instant.getEpochSecond());
        out.writeInt(instant.getNano());
      }
      case Enum<?> constant -> {
        out.writeByte(CONSTANT);
        writeText(out, constant.name());
      }
      case List<?> list -> {
        out.writeByte(LIST);
        out.writeInt(list.size());
        for (Object element : list) {
          write(out, element);
        }
      }
      case Record record -> writeRecord(out, record);
      default ->
          throw new IllegalStateException(
              "No fingerprint encoding for " + value.getClass().getName());
    }
  }

  private static void writeRecord(DataOutputStream out, Record record) throws IOException {
    RecordComponent[] components = record.getClass().getRecordComponents();
    out.writeByte(RECORD);
    out.writeInt(components.length);
    for (RecordComponent component : components) {
      write(out, valueOf(component, record));
    }
  }

  private static @Nullable Object valueOf(RecordComponent component, Record record) {
    try {
      return component.getAccessor().invoke(record);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new IllegalStateException("Cannot read " + component.getName(), e);
    }
  }

  private static void writeText(DataOutputStream out, String text) throws IOException {
    byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
    out.writeInt(utf8.length);
    out.write(utf8);
  }

  private static String canonical(BigDecimal number) {
    return number.stripTrailingZeros().toPlainString();
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
