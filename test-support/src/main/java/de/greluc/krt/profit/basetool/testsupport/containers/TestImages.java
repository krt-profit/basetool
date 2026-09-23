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
 * Container images the integration tests start through Testcontainers, pinned to exactly what
 * production runs.
 *
 * <p>Why this exists (audit item TST-18): five Redis integration tests in three modules each wrote
 * {@code redis:7-alpine} by hand while production ran {@code redis:8-alpine} pinned by digest, so
 * the Spring Session hash repair, the live-sync and notification pub/sub fan-out and the ingest
 * handoff staging were all verified against a major version nobody deploys. One constant here, used
 * by all five, makes the test image the production image.
 *
 * <p>The value is a copy, and a copy can drift, so it is guarded: {@code TestImagesTest} (this
 * module) reads the {@code x-redis} anchor of {@code docker-compose.yml} and the generated {@code
 * quadlet/systemd/redis.container} and fails the build unless both name this exact reference. A
 * digest bump therefore fails that test until this constant moves with it, in the same change. For
 * a Dependabot {@code docker-compose} PR that moves the Redis digest this is one more line in the
 * maintainer commit that PR already needs, because the Quadlet unit has to be regenerated from the
 * compose file anyway ({@code scripts/generate-quadlet.py}; repo-lint's drift check refuses the PR
 * until it is).
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
