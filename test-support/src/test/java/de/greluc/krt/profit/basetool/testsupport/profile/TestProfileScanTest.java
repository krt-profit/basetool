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

package de.greluc.krt.profit.basetool.testsupport.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestProfileScanTest {

  @Test
  void reportsEveryRedundantSpellingAndKeepsAnyOtherProfile(@TempDir Path root) throws IOException {
    write(
        root.resolve("a/PlainTest.java"), "@SpringBootTest\n@ActiveProfiles(\"test\")\nclass A {}");
    write(
        root.resolve("a/b/QualifiedTest.java"),
        "@org.springframework.test.context.ActiveProfiles( \"test\" )\nclass B {}");
    write(root.resolve("ArrayTest.java"), "@ActiveProfiles({\"test\"})\nclass C {}");
    write(root.resolve("NamedTest.java"), "@ActiveProfiles(profiles = \"test\")\nclass D {}");
    write(
        root.resolve("SecondProfileTest.java"),
        "@ActiveProfiles({\"test\", \"prod\"})\nclass E {}");
    write(root.resolve("OtherProfileTest.java"), "@ActiveProfiles(\"prod\")\nclass F {}");
    write(root.resolve("NoneTest.java"), "@SpringBootTest\nclass G {}");

    assertThat(TestProfileScan.redundantTestProfiles(root, root.resolve("missing")))
        .containsExactlyInAnyOrder(
            "a/PlainTest.java:2",
            "a/b/QualifiedTest.java:1",
            "ArrayTest.java:1",
            "NamedTest.java:1");
    assertThat(TestProfileScan.javaSourceCount(root, root.resolve("missing"))).isEqualTo(7);
  }

  private static void write(Path file, String text) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }
}
