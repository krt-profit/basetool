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

package de.greluc.krt.profit.basetool.backend.architecture;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.ViolationStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.TreeSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A deterministic {@link ViolationStore} for the frozen module baseline (REQ-MOD-004).
 *
 * <p>Each rule is stored in its own file named after its description, one violation per line,
 * sorted, UTF-8, LF line endings, no header and no index file, so the file diffs cleanly in review.
 * It reads the keys of ArchUnit's default store: {@code default.path}, {@code
 * default.allowStoreCreation} and {@code default.allowStoreUpdate}. An update is always refused
 * when the environment variable {@code CI} is {@code true}: a baseline whose violations were fixed
 * must be shrunk and committed by the change that fixed them, not silently rewritten in a discarded
 * CI checkout.
 */
public final class ModuleBaselineStore implements ViolationStore {

  private static final String PATH_KEY = "default.path";
  private static final String ALLOW_CREATION_KEY = "default.allowStoreCreation";
  private static final String ALLOW_UPDATE_KEY = "default.allowStoreUpdate";
  private static final String DEFAULT_PATH = "src/test/resources/architecture/module-baseline";

  private final boolean fixed;
  private Path folder;
  private boolean creationAllowed;
  private boolean updateAllowed;

  /** Creates a store configured from {@code archunit.properties} when ArchUnit initialises it. */
  public ModuleBaselineStore() {
    this.fixed = false;
    this.folder = Path.of(DEFAULT_PATH);
  }

  /**
   * Creates a store with a fixed folder and fixed permissions that ignores the configuration.
   *
   * @param folder the folder holding one file per rule
   * @param creationAllowed whether a rule without a file may be frozen for the first time
   * @param updateAllowed whether a file may be rewritten after violations were fixed
   */
  public ModuleBaselineStore(@NotNull Path folder, boolean creationAllowed, boolean updateAllowed) {
    this.fixed = true;
    this.folder = folder;
    this.creationAllowed = creationAllowed;
    this.updateAllowed = updateAllowed;
  }

  /**
   * Reads the store keys unless this store was created with a fixed configuration.
   *
   * @param properties the {@code freeze.store} sub-properties of the ArchUnit configuration
   */
  @Override
  public void initialize(@NotNull Properties properties) {
    if (fixed) {
      return;
    }
    folder = Path.of(properties.getProperty(PATH_KEY, DEFAULT_PATH));
    creationAllowed = Boolean.parseBoolean(properties.getProperty(ALLOW_CREATION_KEY, "false"));
    updateAllowed =
        Boolean.parseBoolean(properties.getProperty(ALLOW_UPDATE_KEY, "true"))
            && !"true".equalsIgnoreCase(System.getenv("CI"));
  }

  /**
   * Tells whether a baseline file exists for the rule.
   *
   * @param rule the frozen rule
   * @return {@code true} when the rule has been frozen before
   */
  @Override
  public boolean contains(@NotNull ArchRule rule) {
    return Files.isRegularFile(fileFor(rule));
  }

  /**
   * Writes the rule's violations, sorted, one per line.
   *
   * @param rule the frozen rule
   * @param violations the violations to keep
   * @throws IllegalStateException when creation or update is not allowed, naming what to do
   */
  @Override
  public void save(@NotNull ArchRule rule, @NotNull List<String> violations) {
    Path file = fileFor(rule);
    boolean exists = Files.isRegularFile(file);
    if (!exists && !creationAllowed) {
      throw new IllegalStateException(
          "No frozen baseline at "
              + file
              + " for rule '"
              + rule.getDescription()
              + "' and creating one is disabled (freeze.store.default.allowStoreCreation=false)");
    }
    if (exists && !updateAllowed) {
      int fixedCount = Math.max(0, readLines(file).size() - violations.size());
      throw new IllegalStateException(
          fixedCount
              + " violation(s) frozen in "
              + file
              + " no longer occur; run the backend's ModuleBaselineTest locally and commit the"
              + " shrunk baseline with the change that fixed them");
    }
    TreeSet<String> sorted = new TreeSet<>();
    for (String violation : violations) {
      if (violation.indexOf('\n') >= 0) {
        throw new IllegalStateException("A frozen violation must be one line: " + violation);
      }
      sorted.add(violation);
    }
    StringBuilder text = new StringBuilder();
    for (String line : sorted) {
      text.append(line).append('\n');
    }
    try {
      Files.createDirectories(file.toAbsolutePath().getParent());
      Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Reads the rule's frozen violations.
   *
   * @param rule the frozen rule
   * @return the stored violations in file order
   */
  @Override
  public @NotNull List<String> getViolations(@NotNull ArchRule rule) {
    return readLines(fileFor(rule));
  }

  /**
   * The file that holds a rule's violations.
   *
   * @param rule the frozen rule
   * @return the file, named after the rule description in lower-case words joined by hyphens
   */
  public @NotNull Path fileFor(@NotNull ArchRule rule) {
    return folder.resolve(fileName(rule.getDescription()));
  }

  /**
   * Derives a file name from a rule description.
   *
   * @param description the rule description
   * @return the description in lower-case words joined by hyphens, with a {@code .txt} suffix
   */
  public static @NotNull String fileName(@NotNull String description) {
    String slug =
        description.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    return slug + ".txt";
  }

  private static List<String> readLines(@Nullable Path file) {
    if (file == null || !Files.isRegularFile(file)) {
      return List.of();
    }
    try {
      List<String> lines = new ArrayList<>();
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        if (!line.isBlank()) {
          lines.add(line);
        }
      }
      return lines;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
