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
      "redis:8-alpine@sha256:becdda6c7f4b3fb42e42fd7f120bbf5c54c4caaaf16f26da24e4563d2c1f0576";
}
