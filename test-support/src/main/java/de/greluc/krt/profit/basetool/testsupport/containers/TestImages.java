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
      "redis:8-alpine@sha256:3811787313eba226a2ef38658c6ccb91cd5e110edc89c37767de373120a0e5a0";

  /**
   * The production PostgreSQL image: the {@code 18-alpine} tag pinned to its multi-arch index
   * digest, as {@code docker-compose.yml} and the database Quadlet units declare it. The
   * Testcontainers JDBC URL cannot carry a digest, so {@link PinnedImageSubstitutor} maps the URL's
   * {@code postgres:18-alpine} onto this reference.
   */
  public static final String POSTGRES =
      "postgres:18-alpine@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873";
}
