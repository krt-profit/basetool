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

package de.greluc.krt.profit.basetool.testsupport.containers;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Container images the Testcontainers integration tests start, pinned to what production runs.
 *
 * <p>{@code TestImagesTest} fails unless {@code docker-compose.yml} and the generated Quadlet unit
 * name the same reference.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TestImages {

  /**
   * The production Redis image: the {@code 8-alpine} tag pinned to its multi-arch index digest, as
   * {@code docker-compose.yml} and the Redis Quadlet unit declare it. The tag is kept beside the
   * digest for the reader; the digest is what Docker resolves.
   */
  public static final String REDIS =
      "redis:8-alpine@sha256:21090af93f8324db2f0f0f4f38a7c60369d47d917e000c3423d89d2ae2883537";

  /**
   * The production PostgreSQL image: the {@code 18-alpine} tag pinned to its multi-arch index
   * digest, as {@code docker-compose.yml} and the database Quadlet units declare it. The
   * Testcontainers JDBC URL cannot carry a digest, so the backend's test-classpath image-name
   * substitutor maps the URL's {@code postgres:18-alpine} onto this reference.
   */
  public static final String POSTGRES =
      "postgres:18-alpine@sha256:10a5c69a83035ab5239f5620d708520e8b28f5aa1f2b20143e6024532edadfb5";
}
