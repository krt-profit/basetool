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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * A display-resolution balance envelope retaining every column's first, last and extreme points.
 */
public final class BankBalanceEnvelope {
  /** Horizontal chart columns at one column per PDF point in its 445-point plotting area. */
  static final int COLUMNS = 445;

  private final Instant from;
  private final Instant to;
  private final BigDecimal opening;
  private final Bucket[] buckets = new Bucket[COLUMNS];
  private BigDecimal running;

  /** The exact minimum balance across every movement consumed so far. */
  @Getter private BigDecimal minimum;

  /** The exact maximum balance across every movement consumed so far. */
  @Getter private BigDecimal maximum;

  private long sequence;

  /**
   * Starts a chart envelope at the exact opening balance.
   *
   * @param from inclusive chart start
   * @param to inclusive chart end
   * @param opening initial account balance
   */
  public BankBalanceEnvelope(
      @NotNull Instant from, @NotNull Instant to, @NotNull BigDecimal opening) {
    this.from = from;
    this.to = to;
    this.opening = opening;
    this.running = opening;
    this.minimum = opening;
    this.maximum = opening;
  }

  /**
   * Includes one chronological posting without retaining the posting or its payload.
   *
   * @param at posting instant
   * @param amount signed movement
   */
  public void add(@NotNull Instant at, @NotNull BigDecimal amount) {
    point(at, running);
    running = running.add(amount);
    minimum = minimum.min(running);
    maximum = maximum.max(running);
    point(at, running);
  }

  private void point(Instant at, BigDecimal balance) {
    double start = from.toEpochMilli();
    double end = to.toEpochMilli();
    double timestamp = Math.max(start, Math.min(end, (double) at.toEpochMilli()));
    int column =
        (int) Math.min(COLUMNS - 1, (timestamp - start) / Math.max(1d, end - start) * COLUMNS);
    Vertex vertex = new Vertex(timestamp, balance, sequence++);
    Bucket bucket = buckets[column];
    if (bucket == null) {
      buckets[column] = new Bucket(vertex);
    } else {
      bucket.last = vertex;
      if (balance.compareTo(bucket.minimum.balance()) < 0) {
        bucket.minimum = vertex;
      }
      if (balance.compareTo(bucket.maximum.balance()) > 0) {
        bucket.maximum = vertex;
      }
    }
  }

  /**
   * Returns the chronological viewport vertices, preserving exact global extrema and endpoints.
   *
   * @return at most four vertices per horizontal column plus the two endpoints
   */
  public @NotNull List<double[]> vertices() {
    List<double[]> result = new ArrayList<>(4 * COLUMNS + 2);
    result.add(new double[] {from.toEpochMilli(), opening.doubleValue()});
    for (Bucket bucket : buckets) {
      if (bucket != null) {
        Stream.of(bucket.first, bucket.minimum, bucket.maximum, bucket.last)
            .distinct()
            .sorted(Comparator.comparingLong(Vertex::sequence))
            .forEach(vertex ->
                result.add(new double[] {vertex.timestamp(), vertex.balance().doubleValue()}));
      }
    }
    result.add(new double[] {to.toEpochMilli(), running.doubleValue()});
    return result;
  }

  /** A selected chronological chart point. */
  private record Vertex(double timestamp, BigDecimal balance, long sequence) {}

  /** Constant-space representative points of one horizontal display column. */
  private static final class Bucket {
    private final Vertex first;
    private Vertex last;
    private Vertex minimum;
    private Vertex maximum;

    private Bucket(Vertex first) {
      this.first = first;
      this.last = first;
      this.minimum = first;
      this.maximum = first;
    }
  }
}
