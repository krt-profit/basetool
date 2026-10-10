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

package de.greluc.krt.profit.basetool.frontend;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import de.greluc.krt.profit.basetool.frontend.contract.DtoMirrorScan;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that every frontend {@link DtoMirror} record mirrors all record components of its
 * backend twin, by parsing both source trees (REQ-OPS-038).
 *
 * <p>A twin is the backend record of the same simple name anywhere under {@code
 * backend/src/main/java}, nested records included, or the one {@link #RENAMED_TWINS} names. A
 * frontend record with no twin fails unless {@link #UNPAIRED_BY_DESIGN} lists it; a backend-only
 * component fails unless {@link #ALLOWED_BACKEND_ONLY_FIELDS} lists it; a frontend-only component
 * is only reported. The frontend sources are those of the top-level {@link DtoMirror} types; the
 * backend tree is walked recursively.
 */
class DtoMirrorConsistencyTest {

  private static final Path FRONTEND_SOURCE_ROOT = resolveModuleRelative("src/main/java");
  private static final Path BACKEND_SOURCE_ROOT = resolveModuleRelative("../backend/src/main/java");

  /** The number of mirrors paired today; fewer means the scan lost its sources. */
  private static final int PAIRED_FLOOR = 267;

  /** Frontend mirrors whose backend twin carries another name: frontend name to backend name. */
  private static final Map<String, String> RENAMED_TWINS =
      Map.ofEntries(
          entry("AdminDeletionRequestDto", "DeletionRequestDto"),
          entry("DefaultBlueprintDto", "DefaultBlueprintResponse"),
          entry("MaterialCreateAjaxRequest", "MaterialCreateDto"),
          entry("MemberEvaluationDto", "MemberEvaluationResponse"),
          entry("NotificationCountResponse", "NotificationUnreadCountDto"),
          entry("PersonSearchResultDto", "PersonSearchResult"),
          entry("PersonalBlueprintBatchResultDto", "PersonalBlueprintBatchResult"),
          entry("UserAttributesUpdateDto", "UserAttributesRequest"),
          entry("PersonalBlueprintBulkDeleteResultDto", "PersonalBlueprintBulkDeleteResult"),
          entry("PersonalBlueprintDto", "PersonalBlueprintResponse"),
          entry("PersonalBlueprintRecipeDto", "PersonalBlueprintRecipeResponse"),
          entry("PersonalInventoryItemDto", "PersonalInventoryItemResponse"),
          entry("PromotionCategoryDto", "PromotionCategoryResponse"),
          entry("PromotionEligibilityDto", "PromotionEligibilityResponse"),
          entry("PromotionLevelContentDto", "PromotionLevelContentResponse"),
          entry("PromotionRequirementCheckDto", "PromotionRequirementCheckResponse"),
          entry("PromotionTopicDto", "PromotionTopicResponse"),
          entry("RankRequirementDto", "RankRequirementResponse"));

  /** Frontend records that deliberately have no backend record twin, each with its reason. */
  private static final Map<String, String> UNPAIRED_BY_DESIGN =
      Map.ofEntries(
          entry("AuditRowView", "page view model merged from BankAuditEventDto and AuditEventDto"),
          entry("BereichCreateRequest", "write subset of BereichDto; the backend assigns the rest"),
          entry("DefaultBlueprintAddResultDto", "toast outcome the frontend counts itself"),
          entry(
              "DefaultBlueprintAddSelectionRequest",
              "browser body expanded into one DefaultBlueprintCreateRequest per key"),
          entry("MaterialUpdateAjaxRequest", "browser body; updateType selects the backend call"),
          entry("MatrixGridDto", "render projection the frontend builds for the matrix grid"),
          entry(
              "MissionActualTimeUpdateRequest",
              "browser body turned into a PatchMissionScheduleRequest"),
          entry("NotificationPageSliceDto", "localized inbox page the frontend assembles"),
          entry("NotificationViewDto", "localized notification view model"),
          entry(
              "OrganisationsleitungCreateRequest",
              "write subset of OrganisationsleitungDto; the backend assigns the rest"),
          entry("StagedHandoff", "mirror of the ingest gateway's Redis value, not of the backend"));

  /**
   * Per-DTO whitelist of backend-only record components the frontend deliberately does not mirror.
   * Each field is listed explicitly so a new backend-only field still fails.
   */
  private static final Map<String, Set<String>> ALLOWED_BACKEND_ONLY_FIELDS =
      Map.of("MaterialCreateAjaxRequest", Set.of("isManualEntry"));

  /** A record declaration at the start of a line, nested ones included. */
  private static final Pattern RECORD_DECLARATION =
      Pattern.compile(
          "^\\s*(?:(?:public|protected|private|static|final)\\s+)*record\\s+(\\w+)"
              + "\\s*(?:<[^>]+>)?\\s*\\(",
          Pattern.MULTILINE);

  /**
   * Resolves a path that is given relative to the frontend module root, also when the working
   * directory is the repository root.
   *
   * @param relative the path below the frontend module root
   * @return the resolved path
   */
  private static Path resolveModuleRelative(String relative) {
    Path direct = Paths.get(relative);
    if (Files.exists(direct)) {
      return direct;
    }
    Path fromRepoRoot = Paths.get("frontend").resolve(relative);
    if (Files.exists(fromRepoRoot)) {
      return fromRepoRoot;
    }
    return direct;
  }

  @Test
  void everyFrontendDtoMirrorMustNotMissBackendRecordComponents() throws IOException {
    assertTrue(
        Files.isDirectory(FRONTEND_SOURCE_ROOT),
        "Frontend source root not found at " + FRONTEND_SOURCE_ROOT.toAbsolutePath());
    assertTrue(
        Files.isDirectory(BACKEND_SOURCE_ROOT),
        "Backend source root not found at " + BACKEND_SOURCE_ROOT.toAbsolutePath());

    List<Path> mirrors =
        DtoMirrorScan.topLevelTypes().stream()
            .map(type -> FRONTEND_SOURCE_ROOT.resolve(type.getName().replace('.', '/') + ".java"))
            .toList();
    assertThat(mirrors).as("@DtoMirror sources").allMatch(Files::isRegularFile);

    Pairing pairing = pair(mirrors, BACKEND_SOURCE_ROOT, RENAMED_TWINS, UNPAIRED_BY_DESIGN);

    assertThat(pairing.paired())
        .as("paired mirrors; fewer means the scan lost its sources or its layout")
        .isGreaterThanOrEqualTo(PAIRED_FLOOR);
    assertThat(pairing.listErrors())
        .as("RENAMED_TWINS / UNPAIRED_BY_DESIGN entries that no longer hold")
        .isEmpty();
    assertThat(pairing.unpaired())
        .as(
            "frontend DTO records without a backend twin: add the twin, name it in RENAMED_TWINS,"
                + " or list the record in UNPAIRED_BY_DESIGN with its reason")
        .isEmpty();

    List<String> drifts = new ArrayList<>();
    List<String> softWarnings = new ArrayList<>();
    pairing
        .pairs()
        .forEach(
            (name, pair) -> {
              Set<String> missingOnFrontend = new LinkedHashSet<>(pair.backend());
              missingOnFrontend.removeAll(pair.frontend());
              missingOnFrontend.removeAll(ALLOWED_BACKEND_ONLY_FIELDS.getOrDefault(name, Set.of()));
              if (!missingOnFrontend.isEmpty()) {
                drifts.add(
                    name
                        + " — backend record has components the frontend mirror is missing: "
                        + missingOnFrontend
                        + ". A Thymeleaf template that references any of these will 500 at render"
                        + " time. Add them to the frontend record (preferred) or, if intentional,"
                        + " list them in ALLOWED_BACKEND_ONLY_FIELDS with a rationale.");
              }
              Set<String> extraOnFrontend = new LinkedHashSet<>(pair.frontend());
              extraOnFrontend.removeAll(pair.backend());
              if (!extraOnFrontend.isEmpty()) {
                softWarnings.add(name + " — frontend-only record components: " + extraOnFrontend);
              }
            });

    if (!softWarnings.isEmpty()) {
      System.out.println("DTO mirror soft warnings (frontend-only fields):");
      softWarnings.forEach(w -> System.out.println("  " + w));
    }

    if (!drifts.isEmpty()) {
      fail(
          "DTO mirror drift detected (the recurring 'Property or field cannot be found' Thymeleaf"
              + " bug class):\n  "
              + String.join("\n  ", drifts));
    }
  }

  @Test
  void aMirrorWithoutATwinFailsAndATwinIsFoundInAnyFolder(@TempDir Path root) throws IOException {
    Path frontend = root.resolve("frontend");
    Path backend = root.resolve("backend");
    write(frontend.resolve("mission/MissionDto.java"), "public record MissionDto(String a) {}");
    write(frontend.resolve("OrphanDto.java"), "public record OrphanDto(String a) {}");
    write(frontend.resolve("ViewDto.java"), "public record ViewDto(String a) {}");
    write(frontend.resolve("RenamedDto.java"), "public record RenamedDto(String a) {}");
    write(frontend.resolve("TwinnedDto.java"), "public record TwinnedDto(String a) {}");
    write(frontend.resolve("Kind.java"), "public enum Kind { A }");
    write(
        backend.resolve("de/x/mission/api/MissionController.java"),
        "class MissionController {\n  public record MissionDto(String a, int b) {}\n}");
    write(
        backend.resolve("de/x/RenamedResponse.java"), "public record RenamedResponse(String a) {}");
    write(backend.resolve("de/x/TwinnedDto.java"), "public record TwinnedDto(String a) {}");

    Pairing pairing =
        pair(
            frontend,
            backend,
            Map.of("RenamedDto", "RenamedResponse", "GoneDto", "X"),
            Map.of("ViewDto", "view model", "TwinnedDto", "excused although it has a twin"));

    assertThat(pairing.unpaired()).containsExactly("OrphanDto");
    assertThat(pairing.pairs()).containsOnlyKeys("MissionDto", "RenamedDto");
    assertThat(pairing.pairs().get("MissionDto").backend()).containsExactly("a", "b");
    assertThat(pairing.paired()).isEqualTo(2);
    assertThat(pairing.listErrors())
        .containsExactlyInAnyOrder(
            "RENAMED_TWINS names GoneDto, which is no frontend DTO record",
            "UNPAIRED_BY_DESIGN lists TwinnedDto, which has a backend twin");
  }

  /**
   * One frontend record and its backend twin's components.
   *
   * @param frontend the frontend record's component names
   * @param backend the backend twin's component names
   */
  record Pair(@NotNull List<String> frontend, @NotNull List<String> backend) {}

  /**
   * The outcome of pairing the frontend records with their backend twins.
   *
   * @param pairs paired frontend record name to its components and its twin's
   * @param unpaired frontend records with no twin and no listed reason, sorted
   * @param listErrors list entries that name no record, or excuse a record that has a twin
   */
  record Pairing(
      @NotNull Map<String, Pair> pairs,
      @NotNull List<String> unpaired,
      @NotNull List<String> listErrors) {

    /**
     * Counts the paired records.
     *
     * @return the number of frontend records compared against a twin
     */
    int paired() {
      return pairs.size();
    }
  }

  /**
   * Pairs every frontend record below one root with its backend twin below another.
   *
   * @param frontendRoot the frontend DTO directory, walked recursively
   * @param backendRoot the backend source root, walked recursively
   * @param renamed frontend name to backend name for twins under another name
   * @param unpairedByDesign frontend records that have no twin, with reasons
   * @return the pairs, the unexcused unpaired records and the stale list entries
   * @throws IOException if a source cannot be read
   */
  static Pairing pair(
      Path frontendRoot,
      Path backendRoot,
      Map<String, String> renamed,
      Map<String, String> unpairedByDesign)
      throws IOException {
    return pair(javaSources(frontendRoot), backendRoot, renamed, unpairedByDesign);
  }

  /**
   * Pairs the frontend records of the given sources with their backend twins below a root.
   *
   * @param frontendSources the frontend sources, one top-level record each
   * @param backendRoot the backend source root, walked recursively
   * @param renamed frontend name to backend name for twins under another name
   * @param unpairedByDesign frontend records that have no twin, with reasons
   * @return the pairs, the unexcused unpaired records and the stale list entries
   * @throws IOException if a source cannot be read
   */
  static Pairing pair(
      List<Path> frontendSources,
      Path backendRoot,
      Map<String, String> renamed,
      Map<String, String> unpairedByDesign)
      throws IOException {
    Map<String, List<List<String>>> backendRecords = new TreeMap<>();
    for (Path file : javaSources(backendRoot)) {
      String source = Files.readString(file, StandardCharsets.UTF_8);
      Matcher declaration = RECORD_DECLARATION.matcher(source);
      while (declaration.find()) {
        List<String> components = componentsAfter(source, declaration.end());
        if (components != null) {
          backendRecords
              .computeIfAbsent(declaration.group(1), k -> new ArrayList<>())
              .add(components);
        }
      }
    }

    Map<String, Pair> pairs = new TreeMap<>();
    List<String> unpaired = new ArrayList<>();
    List<String> listErrors = new ArrayList<>();
    Set<String> frontendRecords = new LinkedHashSet<>();
    for (Path file : frontendSources) {
      String name = file.getFileName().toString().replaceFirst("\\.java$", "");
      List<String> frontend = recordComponents(Files.readString(file, StandardCharsets.UTF_8));
      if (frontend == null) {
        continue;
      }
      frontendRecords.add(name);
      List<List<String>> twins = backendRecords.get(renamed.getOrDefault(name, name));
      if (unpairedByDesign.containsKey(name)) {
        if (twins != null) {
          listErrors.add("UNPAIRED_BY_DESIGN lists " + name + ", which has a backend twin");
        }
        continue;
      }
      if (twins == null) {
        unpaired.add(name);
      } else if (twins.size() > 1) {
        listErrors.add(name + " has " + twins.size() + " backend records of that name");
      } else {
        pairs.put(name, new Pair(frontend, twins.getFirst()));
      }
    }
    renamed.keySet().stream()
        .filter(name -> !frontendRecords.contains(name))
        .sorted()
        .forEach(
            name ->
                listErrors.add(
                    "RENAMED_TWINS names " + name + ", which is no frontend DTO record"));
    unpairedByDesign.keySet().stream()
        .filter(name -> !frontendRecords.contains(name))
        .sorted()
        .forEach(
            name ->
                listErrors.add(
                    "UNPAIRED_BY_DESIGN lists " + name + ", which is no frontend DTO record"));
    unpaired.sort(String::compareTo);
    return new Pairing(pairs, unpaired, listErrors);
  }

  /**
   * Lists every Java source below a root, sorted.
   *
   * @param root the directory to walk
   * @return the {@code .java} files
   * @throws IOException if the tree cannot be walked
   */
  private static List<Path> javaSources(Path root) throws IOException {
    try (Stream<Path> tree = Files.walk(root)) {
      return tree.filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".java"))
          .sorted()
          .toList();
    }
  }

  /**
   * Writes a fixture source, creating its folders.
   *
   * @param file the file to write
   * @param text its content
   * @throws IOException if it cannot be written
   */
  private static void write(Path file, String text) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }

  /**
   * Returns the component names of the first top-level {@code public record} in a source.
   *
   * @param source a Java source
   * @return the component names, or {@code null} when the source declares no public record
   */
  private static @Nullable List<String> recordComponents(String source) {
    Matcher matcher =
        Pattern.compile("public\\s+record\\s+(\\w+)\\s*(?:<[^>]+>)?\\s*\\(").matcher(source);
    return matcher.find() ? componentsAfter(source, matcher.end()) : null;
  }

  /**
   * Returns the component names of a record header whose opening parenthesis ends just before
   * {@code cursor}; annotations, generics and nested parentheses are skipped by depth tracking.
   *
   * @param source a Java source
   * @param cursor the index just after the header's opening parenthesis
   * @return the component names, or {@code null} when the header does not close
   */
  private static @Nullable List<String> componentsAfter(String source, int cursor) {
    int depth = 1;
    int headerEnd = cursor;
    while (headerEnd < source.length() && depth > 0) {
      char c = source.charAt(headerEnd);
      switch (c) {
        case '(' -> depth++;
        case ')' -> depth--;
        default -> {}
      }
      headerEnd++;
    }
    if (depth != 0) {
      return null;
    }
    String body = source.substring(cursor, headerEnd - 1);
    return splitTopLevelByComma(body).stream()
        .map(DtoMirrorConsistencyTest::extractParameterName)
        .filter(name -> name != null && !name.isEmpty())
        .toList();
  }

  /**
   * Splits the record header body on commas that sit at depth 0; parentheses, angle brackets and
   * square brackets count as nesting.
   *
   * @param body the text between a record header's parentheses
   * @return the component declarations
   */
  private static List<String> splitTopLevelByComma(String body) {
    List<String> result = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    int depth = 0;
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(i);
      if (c == '<' || c == '(' || c == '[') {
        depth++;
      } else if (c == '>' || c == ')' || c == ']') {
        depth--;
      } else if (c == ',' && depth == 0) {
        result.add(cur.toString());
        cur.setLength(0);
        continue;
      }
      cur.append(c);
    }
    if (!cur.isEmpty()) {
      result.add(cur.toString());
    }
    return result;
  }

  /**
   * Reduces one component declaration to its name, after its leading annotations.
   *
   * @param component a declaration such as {@code @NotNull String name}
   * @return the name, or {@code null} when nothing is left
   */
  private static @Nullable String extractParameterName(String component) {
    String stripped = stripLeadingAnnotations(component.trim());
    if (stripped.isEmpty()) {
      return null;
    }
    String[] tokens = stripped.split("\\s+");
    return tokens[tokens.length - 1].trim();
  }

  /**
   * Drops the leading {@code @Annotation} and {@code @Annotation(args)} tokens of a declaration.
   *
   * @param s a component declaration
   * @return the declaration without its leading annotations
   */
  private static String stripLeadingAnnotations(String s) {
    int i = 0;
    while (i < s.length() && s.charAt(i) == '@') {
      i++;
      while (i < s.length()
          && (Character.isJavaIdentifierPart(s.charAt(i)) || s.charAt(i) == '.')) {
        i++;
      }
      if (i < s.length() && s.charAt(i) == '(') {
        int depth = 1;
        i++;
        while (i < s.length() && depth > 0) {
          char c = s.charAt(i);
          if (c == '(') {
            depth++;
          } else if (c == ')') {
            depth--;
          }
          i++;
        }
      }
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
        i++;
      }
    }
    return s.substring(i);
  }
}
