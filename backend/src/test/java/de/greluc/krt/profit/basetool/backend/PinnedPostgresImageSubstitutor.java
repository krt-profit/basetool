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

package de.greluc.krt.profit.basetool.backend;

import de.greluc.krt.profit.basetool.testsupport.containers.TestImages;
import org.jetbrains.annotations.NotNull;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.ImageNameSubstitutor;

/**
 * Testcontainers image-name substitutor that replaces the tag-only PostgreSQL image of the
 * backend's {@code jdbc:tc:postgresql:<tag>} URL with the digest-pinned {@link TestImages#POSTGRES}
 * (TST-18).
 *
 * <p>Activated by {@code image.substitutor} in the backend's test-classpath {@code
 * testcontainers.properties}. Only the exact tagged reference {@link #TAGGED_POSTGRES} is replaced;
 * every other image passes through unchanged.
 */
public final class PinnedPostgresImageSubstitutor extends ImageNameSubstitutor {

  /** {@link TestImages#POSTGRES} without its digest: the reference a JDBC URL can express. */
  public static final String TAGGED_POSTGRES =
      TestImages.POSTGRES.substring(0, TestImages.POSTGRES.indexOf('@'));

  /**
   * Returns {@link TestImages#POSTGRES} for {@link #TAGGED_POSTGRES} and {@code original}
   * otherwise.
   *
   * @param original the image a container was declared with
   * @return the digest-pinned PostgreSQL image, or {@code original}
   */
  @Override
  public @NotNull DockerImageName apply(@NotNull DockerImageName original) {
    if (TAGGED_POSTGRES.equals(original.asCanonicalNameString())) {
      return DockerImageName.parse(TestImages.POSTGRES);
    }
    return original;
  }

  /**
   * Names this substitutor in Testcontainers' substitution log lines.
   *
   * @return a fixed description naming the pinned image
   */
  @Override
  protected @NotNull String getDescription() {
    return "pin " + TAGGED_POSTGRES + " to " + TestImages.POSTGRES;
  }
}
