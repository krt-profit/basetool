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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Keeps the committed Terms-of-Use version honest (REQ-SEC-028).
 *
 * <p>The version is a digest of the terms wording, and it is <strong>committed</strong> to {@code
 * backend/src/main/resources/terms-version.properties} rather than generated during the build. The
 * original reason was a cross-module read: the digest hashed a <em>frontend</em> source file, and
 * the backend Docker image copies only {@code frontend/build.gradle.kts} for layer caching, so the
 * image build died with "Input file does not exist" while every local build stayed green. That
 * particular hazard is gone — the wording moved into this module's own bundle with ADR-0138 — but
 * the artifact stays committed, because a generated one would still have to be produced before the
 * image is assembled and there is nothing to gain from re-acquiring that coupling.
 *
 * <p>What a committed artifact loses is the guarantee that it matches the text, which is the whole
 * point of deriving it. This test is that guarantee: it re-derives the digest from the German
 * bundle and fails when the committed file disagrees — so editing the terms and forgetting to
 * regenerate breaks the build instead of silently leaving everyone consented to wording that no
 * longer exists.
 */
class TermsVersionParityTest {

  /**
   * The authoritative wording; path is relative to the backend module directory.
   *
   * <p>This module's own bundle since ADR-0138. It has to be the bundle the document endpoint
   * serves: pointed anywhere else, the digest would keep hashing text nobody reads, and a wording
   * change would ship with an unchanged version — a consent gate that never re-prompts and gives no
   * sign it has stopped working.
   */
  private static final Path BUNDLE = Path.of("src/main/resources/messages_de.properties");

  /** The committed artifact {@link TermsVersionProvider} reads at runtime. */
  private static final Path COMMITTED = Path.of("src/main/resources/terms-version.properties");

  /** Key inside the committed file. */
  private static final String VERSION_KEY = "basetool.terms.version=";

  /**
   * Asserts the committed version is the digest of the terms wording currently in the bundle.
   *
   * @throws IOException if either file cannot be read
   * @throws NoSuchAlgorithmException never — SHA-256 is mandated by the JDK
   */
  @Test
  void committedVersionMatchesTheTermsWording() throws IOException, NoSuchAlgorithmException {
    String committed = Files.readString(COMMITTED, StandardCharsets.UTF_8).trim();
    assertThat(committed).startsWith(VERSION_KEY);

    String expected = deriveDigest();

    assertThat(committed.substring(VERSION_KEY.length()))
        .as(
            "The Terms-of-Use wording changed but %s was not regenerated. "
                + "Run: ./gradlew generateTermsVersion",
            COMMITTED)
        .isEqualTo(expected);
  }

  /**
   * Re-derives the version exactly as the root {@code generateTermsVersion} task does: every {@code
   * terms.*} line of the German bundle, sorted, newline-joined, SHA-256, first 16 hex chars.
   *
   * @return the derived version
   * @throws IOException if the bundle cannot be read
   * @throws NoSuchAlgorithmException never — SHA-256 is mandated by the JDK
   */
  private static String deriveDigest() throws IOException, NoSuchAlgorithmException {
    List<String> lines = Files.readAllLines(BUNDLE, StandardCharsets.UTF_8);
    String clauses =
        lines.stream()
            .filter(line -> line.startsWith("terms.") && line.contains("="))
            .sorted()
            .collect(Collectors.joining("\n"));
    assertThat(clauses).as("no terms.* entries found in %s", BUNDLE).isNotEmpty();

    byte[] digest =
        MessageDigest.getInstance("SHA-256").digest(clauses.getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder();
    for (byte b : digest) {
      hex.append(String.format("%02x", b));
    }
    return hex.substring(0, 16);
  }
}
