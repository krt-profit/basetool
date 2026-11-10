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

package de.greluc.krt.profit.basetool.backend.platform.api;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;

/** Conservative retained-value admission weights for immutable cache snapshots. */
public final class CacheValueWeight {
  private static final int OBJECT_BYTES = 32;
  private static final int REFERENCE_BYTES = 8;
  private static final int COLLECTION_BYTES = 64;
  private static final int MAP_ENTRY_BYTES = 64;
  private static final int PAGE_BYTES = 256;
  private static final int KEY_BYTES = 256;
  private static final int SCALAR_BYTES = 64;
  private static final int CHARACTER_BYTES = 2;
  private static final ClassValue<Method[]> ACCESSORS =
      new ClassValue<>() {
        @Override
        protected Method[] computeValue(Class<?> type) {
          return java.util.Arrays.stream(type.getRecordComponents())
              .map(java.lang.reflect.RecordComponent::getAccessor)
              .toArray(Method[]::new);
        }
      };

  private CacheValueWeight() {}

  /**
   * Weighs a retained value graph, rejecting unknown shapes or values above the admission limit.
   *
   * @param key cache lookup key
   * @param value immutable cached snapshot
   * @param maximumBytes admission ceiling for this cache
   * @return estimated retained bytes, or {@link Integer#MAX_VALUE} when admission is refused
   */
  public static int weigh(@Nullable Object key, @NotNull Object value, int maximumBytes) {
    long bytes = KEY_BYTES;
    if (key != null) {
      bytes += (long) key.toString().length() * CHARACTER_BYTES;
    }
    var pending = new ArrayDeque<Object>();
    var seen = new IdentityHashMap<Object, Boolean>();
    pending.add(value);
    while (!pending.isEmpty()) {
      Object current = pending.removeLast();
      if (seen.put(current, Boolean.TRUE) != null) {
        continue;
      }
      if (current instanceof String text) {
        bytes += OBJECT_BYTES + (long) text.length() * CHARACTER_BYTES;
      } else if (current instanceof Page<?> page) {
        bytes += PAGE_BYTES;
        pending.add(page.getContent());
      } else if (current instanceof Map<?, ?> map) {
        bytes += COLLECTION_BYTES + (long) map.size() * MAP_ENTRY_BYTES;
        if (bytes > maximumBytes) {
          return Integer.MAX_VALUE;
        }
        for (var entry : map.entrySet()) {
          add(pending, entry.getKey());
          add(pending, entry.getValue());
        }
      } else if (current instanceof Collection<?> collection) {
        bytes += COLLECTION_BYTES + (long) collection.size() * REFERENCE_BYTES;
        if (bytes > maximumBytes) {
          return Integer.MAX_VALUE;
        }
        collection.forEach(element -> add(pending, element));
      } else if (current instanceof Optional<?> optional) {
        bytes += OBJECT_BYTES;
        optional.ifPresent(pending::add);
      } else if (current instanceof BigInteger integer) {
        bytes += SCALAR_BYTES + (long) integer.bitLength() / Byte.SIZE;
      } else if (current instanceof BigDecimal decimal) {
        bytes += SCALAR_BYTES * 2L + (long) decimal.unscaledValue().bitLength() / Byte.SIZE;
      } else if (current instanceof Integer
          || current instanceof Long
          || current instanceof Short
          || current instanceof Byte
          || current instanceof Float
          || current instanceof Double
          || current instanceof Boolean
          || current instanceof Character
          || current instanceof UUID
          || current instanceof Enum<?>
          || current instanceof TemporalAccessor) {
        bytes += SCALAR_BYTES;
      } else if (current.getClass().isArray()) {
        int length = Array.getLength(current);
        bytes += OBJECT_BYTES + (long) length * REFERENCE_BYTES;
        if (!current.getClass().getComponentType().isPrimitive()) {
          for (int index = 0; index < length; index++) {
            add(pending, Array.get(current, index));
          }
        }
      } else if (current.getClass().isRecord()) {
        Method[] accessors = ACCESSORS.get(current.getClass());
        bytes += OBJECT_BYTES + (long) accessors.length * REFERENCE_BYTES;
        try {
          for (Method accessor : accessors) {
            add(pending, accessor.invoke(current));
          }
        } catch (ReflectiveOperationException _) {
          return Integer.MAX_VALUE;
        }
      } else {
        return Integer.MAX_VALUE;
      }
      if (bytes > maximumBytes) {
        return Integer.MAX_VALUE;
      }
    }
    return (int) bytes;
  }

  private static void add(ArrayDeque<Object> pending, @Nullable Object value) {
    if (value != null) {
      pending.add(value);
    }
  }
}
