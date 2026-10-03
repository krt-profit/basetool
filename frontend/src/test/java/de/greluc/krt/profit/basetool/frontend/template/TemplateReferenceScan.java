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

package de.greluc.krt.profit.basetool.frontend.template;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Static scans that resolve the string references between Java, SpEL and Thymeleaf templates which
 * the compiler cannot see (REQ-FE-026): {@code T(fqcn)} type references in templates, and view
 * names and fragment selectors in controller sources and templates.
 */
final class TemplateReferenceScan {

  /** The template root, relative to the module directory Gradle runs tests in. */
  static final Path TEMPLATE_ROOT = Path.of("src", "main", "resources", "templates");

  /** The main Java source root, relative to the module directory. */
  static final Path JAVA_ROOT = Path.of("src", "main", "java");

  /** Any {@code T(} a SpEL expression could start a type reference with. */
  private static final Pattern LOOSE_TYPE_REFERENCE = Pattern.compile("(?<![\\w$.])T\\s*\\(");

  /** A {@code T(fqcn)} reference with an optional {@code .member} and call parenthesis. */
  private static final Pattern TYPE_REFERENCE =
      Pattern.compile(
          "(?<![\\w$.])T\\s*\\(\\s*([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*\\)"
              + "(?:\\s*\\.\\s*([A-Za-z_$][\\w$]*)(\\s*\\()?)?");

  /** A view name: a lowercase template path without extension. */
  private static final Pattern VIEW_NAME = Pattern.compile("[a-z][a-z0-9_-]*(?:/[a-z0-9_-]+)*");

  /** A Java string literal without escapes, as view names are written. */
  private static final String LITERAL = "\"([^\"\\\\\\n]*)\"";

  /**
   * Positions in Java source where a literal is a view a handler returns: a returned literal, a
   * switch arm, a {@code ModelAndView} name.
   */
  private static final List<Pattern> VIEW_POSITIONS =
      List.of(
          Pattern.compile("\\breturn\\s+" + LITERAL + "\\s*;"),
          Pattern.compile("(?:\\bcase\\b[^;{}]*|\\bdefault\\s*)->\\s*" + LITERAL + "\\s*;"),
          Pattern.compile("\\bnew\\s+ModelAndView\\s*\\(\\s*" + LITERAL),
          Pattern.compile("\\.setViewName\\s*\\(\\s*" + LITERAL));

  /** A return statement's expression, up to its semicolon. */
  private static final Pattern RETURN_STATEMENT = Pattern.compile("\\breturn\\b([^;]*);");

  /** A literal branch of a conditional expression. */
  private static final List<Pattern> TERNARY_BRANCHES =
      List.of(
          Pattern.compile("\\?\\s*" + LITERAL + "\\s*:"),
          Pattern.compile(":\\s*" + LITERAL + "\\s*$"));

  /** Any Java string literal that selects a fragment of a template. */
  private static final Pattern JAVA_FRAGMENT_LITERAL =
      Pattern.compile("\"([a-z][a-z0-9_/-]*)\\s*::\\s*([^\"\\\\\\n]*)\"");

  /** A fragment expression in a template naming another template by a literal path. */
  private static final Pattern TEMPLATE_FRAGMENT_REFERENCE =
      Pattern.compile(
          "(?:~\\{|th:(?:replace|insert|include)\\s*=\\s*\")\\s*([a-z][a-z0-9_-]*(?:/[a-z0-9_-]+)*)"
              + "\\s*::\\s*([A-Za-z_][\\w-]*)");

  /** A class-level stereotype under which a returned literal is a view name. */
  private static final Pattern VIEW_RETURNING_TYPE =
      Pattern.compile("^@(?:Controller|ControllerAdvice)\\b", Pattern.MULTILINE);

  /** Not instantiable. */
  private TemplateReferenceScan() {}

  /**
   * One {@code T(…)} reference found in a template.
   *
   * @param template the template's path below the template root
   * @param type the fully-qualified type name
   * @param member the member accessed on it, or {@code null}
   * @param call whether the member is invoked as a method
   */
  record TypeReference(
      @NotNull String template, @NotNull String type, @Nullable String member, boolean call) {

    /**
     * Renders the reference as it is reported.
     *
     * @return {@code template: T(type).member}
     */
    @Override
    public @NotNull String toString() {
      return template
          + ": T("
          + type
          + ")"
          + (member == null ? "" : "." + member + (call ? "()" : ""));
    }
  }

  /**
   * One reference to a template, with an optional fragment selector.
   *
   * @param source where the reference was found
   * @param view the template path without extension
   * @param fragment the fragment name, or {@code null} when the whole template or a computed
   *     selector is meant
   */
  record ViewReference(@NotNull String source, @NotNull String view, @Nullable String fragment) {

    /**
     * Renders the reference as it is reported.
     *
     * @return {@code source: view :: fragment}
     */
    @Override
    public @NotNull String toString() {
      return source + ": " + view + (fragment == null ? "" : " :: " + fragment);
    }
  }

  /**
   * Reads every template below {@link #TEMPLATE_ROOT}.
   *
   * @return template path (relative, forward slashes, with extension) to its content
   */
  static @NotNull Map<String, String> templates() {
    return readTree(TEMPLATE_ROOT, ".html");
  }

  /**
   * Reads every Java source below {@link #JAVA_ROOT}.
   *
   * @return source path (relative, forward slashes) to its content
   */
  static @NotNull Map<String, String> javaSources() {
    return readTree(JAVA_ROOT, ".java");
  }

  /**
   * Counts every {@code T(} opener, whether or not it parses as a reference.
   *
   * @param content a template's content
   * @return the number of openers
   */
  static int looseTypeReferenceCount(@NotNull String content) {
    int count = 0;
    Matcher matcher = LOOSE_TYPE_REFERENCE.matcher(content);
    while (matcher.find()) {
      count++;
    }
    return count;
  }

  /**
   * Parses every {@code T(fqcn)} reference of one template.
   *
   * @param template the template's path, for reporting
   * @param content the template's content
   * @return the references in document order
   */
  static @NotNull @Unmodifiable List<TypeReference> typeReferences(
      @NotNull String template, @NotNull String content) {
    List<TypeReference> references = new ArrayList<>();
    Matcher matcher = TYPE_REFERENCE.matcher(content);
    while (matcher.find()) {
      references.add(
          new TypeReference(
              template, matcher.group(1), matcher.group(2), matcher.group(3) != null));
    }
    return List.copyOf(references);
  }

  /**
   * Explains why a type reference would fail at render time.
   *
   * @param reference the reference
   * @param loader the class loader the application renders with
   * @return {@code null} when the type loads and the member is a public static field or method of
   *     it; otherwise the reason
   */
  static @Nullable String unresolved(
      @NotNull TypeReference reference, @NotNull ClassLoader loader) {
    Class<?> type;
    try {
      type = Class.forName(reference.type(), false, loader);
    } catch (ClassNotFoundException | LinkageError ex) {
      return "class does not load";
    }
    String member = reference.member();
    if (member == null) {
      return null;
    }
    if (reference.call()) {
      for (Method method : type.getMethods()) {
        if (method.getName().equals(member) && Modifier.isStatic(method.getModifiers())) {
          return null;
        }
      }
      return "no public static method " + member;
    }
    try {
      Field field = type.getField(member);
      return Modifier.isStatic(field.getModifiers()) ? null : "field " + member + " is not static";
    } catch (NoSuchFieldException ex) {
      return "no public static field " + member;
    }
  }

  /**
   * Finds the views a Java source hands to the view resolver: returned literals of a {@code
   * Controller} or {@code ControllerAdvice}, {@code ModelAndView} names, and every {@code "view ::
   * fragment"} literal of any class.
   *
   * @param source the source's path, for reporting
   * @param javaSource the source's content
   * @return the references, deduplicated
   */
  static @NotNull @Unmodifiable List<ViewReference> javaViewReferences(
      @NotNull String source, @NotNull String javaSource) {
    String content = withoutComments(javaSource);
    TreeMap<String, ViewReference> found = new TreeMap<>();
    if (VIEW_RETURNING_TYPE.matcher(content).find()) {
      List<String> literals = new ArrayList<>();
      for (Pattern position : VIEW_POSITIONS) {
        Matcher matcher = position.matcher(content);
        while (matcher.find()) {
          literals.add(matcher.group(1));
        }
      }
      Matcher statement = RETURN_STATEMENT.matcher(content);
      while (statement.find()) {
        for (Pattern branch : TERNARY_BRANCHES) {
          Matcher matcher = branch.matcher(statement.group(1).strip());
          while (matcher.find()) {
            literals.add(matcher.group(1));
          }
        }
      }
      for (String literal : literals) {
        if (VIEW_NAME.matcher(literal).matches()) {
          found.put(literal, new ViewReference(source, literal, null));
        }
      }
    }
    Matcher fragment = JAVA_FRAGMENT_LITERAL.matcher(content);
    while (fragment.find()) {
      String view = fragment.group(1);
      String selector = fragment.group(2).strip();
      String name = selector.matches("[A-Za-z_][\\w-]*") ? selector : null;
      found.put(view + " :: " + selector, new ViewReference(source, view, name));
    }
    return List.copyOf(found.values());
  }

  /**
   * Finds the literal {@code path :: fragment} references of one template to another.
   *
   * @param template the template's path, for reporting
   * @param content the template's content
   * @return the references in document order
   */
  static @NotNull @Unmodifiable List<ViewReference> templateViewReferences(
      @NotNull String template, @NotNull String content) {
    List<ViewReference> references = new ArrayList<>();
    Matcher matcher = TEMPLATE_FRAGMENT_REFERENCE.matcher(content);
    while (matcher.find()) {
      references.add(new ViewReference(template, matcher.group(1), matcher.group(2)));
    }
    return List.copyOf(references);
  }

  /**
   * Explains why a view reference would fail at render time.
   *
   * @param reference the reference
   * @param templates every template by relative path with extension
   * @return {@code null} when the template exists and declares the fragment; otherwise the reason
   */
  static @Nullable String unresolved(
      @NotNull ViewReference reference, @NotNull Map<String, String> templates) {
    String content = templates.get(reference.view() + ".html");
    if (content == null) {
      return "no template " + reference.view() + ".html";
    }
    String fragment = reference.fragment();
    if (fragment == null) {
      return null;
    }
    Pattern declaration =
        Pattern.compile(
            "th:(?:fragment|ref)\\s*=\\s*[\"']\\s*" + Pattern.quote(fragment) + "\\s*[\"'(]");
    return declaration.matcher(content).find()
        ? null
        : "template " + reference.view() + ".html declares no fragment " + fragment;
  }

  /**
   * Blanks out every comment of a Java source, leaving string, text-block and character literals
   * intact, so documentation prose is never read as code.
   *
   * @param source the Java source
   * @return the source with each comment character replaced by a space, line breaks kept
   */
  static @NotNull String withoutComments(@NotNull String source) {
    StringBuilder out = new StringBuilder(source.length());
    int i = 0;
    int length = source.length();
    while (i < length) {
      char c = source.charAt(i);
      if (source.startsWith("//", i)) {
        while (i < length && source.charAt(i) != '\n') {
          out.append(' ');
          i++;
        }
      } else if (source.startsWith("/*", i)) {
        int end = source.indexOf("*/", i + 2);
        int stop = end < 0 ? length : end + 2;
        for (; i < stop; i++) {
          out.append(source.charAt(i) == '\n' ? '\n' : ' ');
        }
      } else if (source.startsWith("\"\"\"", i)) {
        int end = source.indexOf("\"\"\"", i + 3);
        int stop = end < 0 ? length : end + 3;
        out.append(source, i, stop);
        i = stop;
      } else if (c == '"' || c == '\'') {
        int j = i + 1;
        while (j < length && source.charAt(j) != c && source.charAt(j) != '\n') {
          j += source.charAt(j) == '\\' ? 2 : 1;
        }
        int stop = Math.min(j + 1, length);
        out.append(source, i, stop);
        i = stop;
      } else {
        out.append(c);
        i++;
      }
    }
    return out.toString();
  }

  /**
   * Reads a source tree.
   *
   * @param root the tree's root
   * @param suffix the file suffix to read
   * @return relative path with forward slashes to file content, sorted by path
   */
  private static @NotNull Map<String, String> readTree(@NotNull Path root, @NotNull String suffix) {
    Map<String, String> files = new TreeMap<>();
    try (Stream<Path> tree = Files.walk(root)) {
      for (Path file : tree.filter(p -> p.toString().endsWith(suffix)).toList()) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        files.put(relative, Files.readString(file, StandardCharsets.UTF_8));
      }
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
    return files;
  }
}
