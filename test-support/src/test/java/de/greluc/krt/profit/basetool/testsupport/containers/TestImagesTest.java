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
 * Verifies that the {@link TestImages} constants equal the images {@code docker-compose.yml} and
 * the Quadlet units run in production, and that the backend's JDBC URL is pinned through {@link
 * PinnedImageSubstitutor}.
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
   * Both database anchors of the compose file name exactly the PostgreSQL constant's image
   * reference.
   *
   * @throws IOException if {@code docker-compose.yml} cannot be read
   */
  @Test
  void thePostgresConstantIsTheComposeFilesImage() throws IOException {
    assertThat(imageLines(repositoryRoot().resolve("docker-compose.yml"), "image: postgres:"))
        .as("docker-compose.yml must pin exactly the PostgreSQL image TestImages.POSTGRES names")
        .containsExactly("image: " + TestImages.POSTGRES, "image: " + TestImages.POSTGRES);
  }

  /**
   * The generated backend-database Quadlet unit names the PostgreSQL constant's image reference,
   * qualified with the registry the host pulls from.
   *
   * @throws IOException if the Quadlet unit cannot be read
   */
  @Test
  void thePostgresConstantIsTheQuadletUnitsImage() throws IOException {
    assertThat(
            imageLines(
                repositoryRoot().resolve("quadlet/systemd/db-backend.container"),
                "Image=docker.io/postgres:"))
        .as("quadlet/systemd/db-backend.container must pin exactly TestImages.POSTGRES")
        .containsExactly("Image=docker.io/" + TestImages.POSTGRES);
  }

  /** The PostgreSQL constant is digest-pinned. */
  @Test
  void thePostgresConstantIsPinnedByDigest() {
    assertThat(TestImages.POSTGRES).startsWith("postgres:18-alpine@sha256:").hasSize(90);
  }

  /**
   * The backend's Testcontainers JDBC URL names the tag {@link PinnedImageSubstitutor} replaces,
   * and the backend's test classpath activates the substitutor, so the tests run the pinned image.
   *
   * @throws IOException if a backend test resource cannot be read
   */
  @Test
  void theBackendJdbcUrlIsSubstitutedWithThePinnedImage() throws IOException {
    Path resources = repositoryRoot().resolve("backend/src/test/resources");
    String tag = PinnedImageSubstitutor.TAGGED_POSTGRES.substring("postgres:".length());
    assertThat(imageLines(resources.resolve("application-test.yml"), "url: jdbc:tc:"))
        .as("the backend's JDBC URL must start the tag PinnedImageSubstitutor pins")
        .containsExactly("url: jdbc:tc:postgresql:" + tag + ":///testdb?TC_DAEMON=true");
    assertThat(imageLines(resources.resolve("testcontainers.properties"), "image.substitutor="))
        .as("the backend's testcontainers.properties must activate PinnedImageSubstitutor")
        .containsExactly("image.substitutor=" + PinnedImageSubstitutor.class.getName());
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
