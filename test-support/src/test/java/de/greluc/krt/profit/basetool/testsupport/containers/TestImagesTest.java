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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link TestImages#REDIS} to the image production actually runs (audit item TST-18).
 *
 * <p>The constant is a copy of the {@code x-redis} image in {@code docker-compose.yml}, which
 * {@code scripts/generate-quadlet.py} carries into {@code quadlet/systemd/redis.container} for the
 * production host. A Dependabot digest bump lands in the compose file; this test then fails until
 * the constant is moved in the same change, so the Redis integration tests can never quietly fall
 * back behind production again. Both files are declared as inputs of this module's {@code test}
 * task, so an edit to either re-runs it.
 */
class TestImagesTest {

  /**
   * The compose file's {@code x-redis} anchor names exactly the constant's image reference.
   *
   * @throws IOException if {@code docker-compose.yml} cannot be read
   */
  @Test
  void theRedisConstantIsTheComposeFilesImage() throws IOException {
    assertThat(imageLines(repositoryRoot().resolve("docker-compose.yml"), "image: redis:"))
        .as("docker-compose.yml must pin exactly the Redis image TestImages.REDIS names")
        .containsExactly("image: " + TestImages.REDIS);
  }

  /**
   * The generated Redis Quadlet unit names the constant's image reference, qualified with the
   * registry the host pulls from.
   *
   * @throws IOException if the Quadlet unit cannot be read
   */
  @Test
  void theRedisConstantIsTheQuadletUnitsImage() throws IOException {
    assertThat(
            imageLines(
                repositoryRoot().resolve("quadlet/systemd/redis.container"),
                "Image=docker.io/redis:"))
        .as(
            "quadlet/systemd/redis.container must pin exactly the Redis image TestImages.REDIS"
                + " names")
        .containsExactly("Image=docker.io/" + TestImages.REDIS);
  }

  /** The constant is digest-pinned, so a rebuilt-in-place tag cannot change what the tests run. */
  @Test
  void theRedisConstantIsPinnedByDigest() {
    assertThat(TestImages.REDIS).startsWith("redis:8-alpine@sha256:").hasSize(86);
  }

  /**
   * Every trimmed line of {@code file} that starts with {@code prefix}, in file order.
   *
   * @param file the file to scan
   * @param prefix the start of an image line
   * @return the matching lines, trimmed; empty when there is none
   * @throws IOException if the file cannot be read
   */
  private static @NotNull List<String> imageLines(@NotNull Path file, @NotNull String prefix)
      throws IOException {
    return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
        .map(String::strip)
        .filter(line -> line.startsWith(prefix))
        .toList();
  }

  /**
   * Walks up from the working directory (the module directory under Gradle) to the directory that
   * holds {@code settings.gradle.kts}.
   *
   * @return the repository root
   */
  private static @NotNull Path repositoryRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
      dir = dir.getParent();
    }
    assertThat(dir).as("no settings.gradle.kts above the working directory").isNotNull();
    return dir;
  }
}
