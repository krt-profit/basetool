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

package de.greluc.krt.profit.basetool.frontend.contract;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves every frontend call to the backend into an HTTP verb and a path template, by parsing the
 * sources with the JDK's own compiler front end (REQ-FE-028).
 *
 * <p>A call is a {@code backendApiClient} verb ({@code get}, {@code post}, {@code put}, {@code
 * patch}, {@code delete}), a {@code backendApiClient.execute(…)} whose request lambda names the
 * verb and URI, or a request on one of the {@link #RAW_CLIENTS}. The URI expression is folded from
 * string literals, {@code +} concatenation, constants of any class, effectively-final locals,
 * {@code String} parameters (through the call sites of their method), helper methods returning a
 * {@code String}, {@code String.format}/{@code formatted}, conditionals and {@code
 * UriComponentsBuilder} chains. Every other operand is a runtime value and becomes a {@link
 * #DYNAMIC} part.
 */
final class BackendCallScanner {

  /** Marks a part of a path template that is only known at runtime. */
  static final char DYNAMIC = '\u0000';

  /** The {@code BackendApiClient} methods that send the request their name says. */
  private static final Set<String> VERB_METHODS = Set.of("get", "post", "put", "patch", "delete");

  /** The fields holding a bare {@code WebClient} that addresses the backend directly. */
  static final Set<String> RAW_CLIENTS = Set.of("sseWebClient", "liveSyncAuthWebClient");

  /** Builder and string methods that keep the path of their receiver unchanged. */
  private static final Set<String> PATH_PRESERVING =
      Set.of(
          "queryParam",
          "queryParams",
          "queryParamIfPresent",
          "replaceQueryParam",
          "replaceQueryParams",
          "query",
          "replaceQuery",
          "fragment",
          "encode",
          "build",
          "buildAndExpand",
          "toUriString",
          "toString",
          "expand",
          "normalize",
          "toUri",
          "cloneBuilder",
          "trim",
          "strip");

  /** Static factories of {@code UriComponentsBuilder} whose first argument is the path. */
  private static final Set<String> BUILDER_ROOTS =
      Set.of("fromPath", "fromUriString", "fromHttpUrl", "fromOriginHeader");

  /** The declared types whose values the scanner folds; any other type is a runtime value. */
  private static final Set<String> TEXTUAL_TYPES =
      Set.of(
          "String",
          "var",
          "CharSequence",
          "Object",
          "StringBuilder",
          "StringBuffer",
          "UriComponentsBuilder",
          "URI");

  /** Methods that extend the path of their receiver by their arguments. */
  private static final Set<String> APPENDING = Set.of("append", "path");

  /** A {@code String.format} conversion that inserts a runtime value. */
  private static final Pattern FORMAT_CONVERSION =
      Pattern.compile("%(?:(\\d+)\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?([a-zA-Z%])");

  /** A URI-template variable such as {@code {id}}. */
  private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{[^}/]*}");

  /** How deep constant, local, parameter and helper resolution may recurse. */
  private static final int MAX_DEPTH = 10;

  /** The cap on alternative templates kept for one expression. */
  private static final int MAX_ALTERNATIVES = 64;

  /**
   * One resolved backend call.
   *
   * @param verb the HTTP method
   * @param template the canonical path template, runtime parts written as {@link #DYNAMIC}
   * @param location {@code File.java:line}
   * @param key the line-independent identity {@code Class#method VERB expression}
   */
  record Call(String verb, String template, String location, String key) {

    /**
     * Renders the call for a failure message.
     *
     * @return {@code VERB template at location}
     */
    @NotNull
    String describe() {
      return verb + " " + display(template) + " at " + location + " [" + key + "]";
    }
  }

  /**
   * A call site whose URI the scanner cannot fold into a template.
   *
   * @param verb the HTTP method, or {@code ?} when the request lambda names none
   * @param location {@code File.java:line}
   * @param key the line-independent identity {@code Class#method VERB expression}
   */
  record Unresolved(String verb, String location, String key) {}

  /**
   * Everything one scan found.
   *
   * @param calls the resolved calls, one entry per alternative template
   * @param callSites the number of call sites with at least one resolved template
   * @param unresolved the call sites the scanner could not fold
   * @param inconsistencies {@code execute(…)} sites whose declared verb or URI differs from the
   *     request their lambda builds
   */
  record Result(
      List<Call> calls, int callSites, List<Unresolved> unresolved, List<String> inconsistencies) {}

  /** A class or interface declaration with its compilation unit and enclosing type. */
  private record TypeInfo(
      String name, ClassTree tree, CompilationUnitTree unit, @Nullable TypeInfo outer) {}

  /** Where an expression is evaluated: its type, its method, bound parameters and the depth. */
  private record Scope(
      TypeInfo type, @Nullable MethodTree method, Map<String, Set<String>> bindings, int depth) {

    /**
     * Returns the same scope one level deeper.
     *
     * @return the deeper scope
     */
    Scope deeper() {
      return new Scope(type, method, bindings, depth + 1);
    }
  }

  private final List<CompilationUnitTree> units;
  private final SourcePositions positions;
  private final Map<String, List<TypeInfo>> typesByName = new HashMap<>();
  private final Map<ClassTree, TypeInfo> typesByTree = new IdentityHashMap<>();

  private BackendCallScanner(List<CompilationUnitTree> units, SourcePositions positions) {
    this.units = units;
    this.positions = positions;
    for (CompilationUnitTree unit : units) {
      for (Tree declaration : unit.getTypeDecls()) {
        if (declaration instanceof ClassTree classTree) {
          index(classTree, unit, null);
        }
      }
    }
  }

  /**
   * Scans every {@code .java} file below a source root.
   *
   * @param sourceRoot the root of the main sources
   * @return the scan result
   */
  @NotNull
  static Result scanDirectory(@NotNull Path sourceRoot) {
    try (Stream<Path> files = Files.walk(sourceRoot)) {
      List<Path> paths = files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
      JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
      StandardJavaFileManager manager =
          compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8);
      List<JavaFileObject> objects = new ArrayList<>();
      manager.getJavaFileObjectsFromPaths(paths).forEach(objects::add);
      return scan(compiler, objects);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Scans in-memory sources, for the scanner's own tests.
   *
   * @param sources file name to Java source
   * @return the scan result
   */
  @NotNull
  static Result scanSources(@NotNull Map<String, String> sources) {
    List<JavaFileObject> objects = new ArrayList<>();
    sources.forEach((name, text) -> objects.add(new InMemorySource(name, text)));
    return scan(ToolProvider.getSystemJavaCompiler(), objects);
  }

  /**
   * Parses the files without attribution and collects the calls.
   *
   * @param compiler the system compiler
   * @param objects the files
   * @return the scan result
   */
  private static Result scan(JavaCompiler compiler, List<JavaFileObject> objects) {
    JavacTask task =
        (JavacTask)
            compiler.getTask(null, null, diagnostic -> {}, List.of("-proc:none"), null, objects);
    List<CompilationUnitTree> units = new ArrayList<>();
    try {
      task.parse().forEach(units::add);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new BackendCallScanner(units, Trees.instance(task).getSourcePositions()).collect();
  }

  /**
   * Renders a template for humans, each runtime part as {@code {}}.
   *
   * @param template the canonical template
   * @return the readable template
   */
  @NotNull
  static String display(@NotNull String template) {
    return template.replace(String.valueOf(DYNAMIC), "{}");
  }

  /**
   * Turns a folded URI into its canonical template: template variables become {@link #DYNAMIC}, the
   * query and fragment are dropped.
   *
   * @param raw the folded URI
   * @return the canonical template
   */
  @NotNull
  static String canonical(@NotNull String raw) {
    String path = TEMPLATE_VARIABLE.matcher(raw).replaceAll(String.valueOf(DYNAMIC));
    for (char end : new char[] {'?', '&', '#'}) {
      int at = path.indexOf(end);
      if (at >= 0) {
        path = path.substring(0, at);
      }
    }
    return path;
  }

  /**
   * Whether a canonical template names a backend resource: it starts with {@code /api/v<n>/} and
   * its first resource segment is literal.
   *
   * @param template the canonical template
   * @return {@code true} when the template is specific enough to be checked
   */
  static boolean isResolved(@NotNull String template) {
    String[] segments = template.split("/", -1);
    return segments.length > 3
        && segments[0].isEmpty()
        && "api".equals(segments[1])
        && segments[2].matches("v\\d+")
        && !segments[3].isEmpty()
        && segments[3].indexOf(DYNAMIC) < 0;
  }

  private void index(ClassTree tree, CompilationUnitTree unit, @Nullable TypeInfo outer) {
    TypeInfo info = new TypeInfo(tree.getSimpleName().toString(), tree, unit, outer);
    typesByName.computeIfAbsent(info.name(), k -> new ArrayList<>()).add(info);
    typesByTree.put(tree, info);
    for (Tree member : tree.getMembers()) {
      if (member instanceof ClassTree nested) {
        index(nested, unit, info);
      }
    }
  }

  private Result collect() {
    List<Call> calls = new ArrayList<>();
    List<Unresolved> unresolved = new ArrayList<>();
    List<String> inconsistencies = new ArrayList<>();
    int[] sites = {0};
    for (CompilationUnitTree unit : units) {
      new TreePathScanner<Void, Void>() {
        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
          Site site = siteOf(getCurrentPath(), unit, node);
          if (site != null) {
            List<Call> found = site.resolve(inconsistencies);
            if (found.isEmpty()) {
              unresolved.add(new Unresolved(site.verb(), site.location(), site.key()));
            } else {
              sites[0]++;
              calls.addAll(found);
            }
          }
          return super.visitMethodInvocation(node, unused);
        }
      }.scan(unit, null);
    }
    return new Result(calls, sites[0], unresolved, inconsistencies);
  }

  /** One call site with the expressions that carry its verb and URI. */
  private final class Site {
    private final String verb;
    private final ExpressionTree uri;
    private final Scope scope;
    private final String location;
    private final String key;
    private final @Nullable MethodInvocationTree execute;

    private Site(
        String verb,
        ExpressionTree uri,
        Scope scope,
        String location,
        String key,
        @Nullable MethodInvocationTree execute) {
      this.verb = verb;
      this.uri = uri;
      this.scope = scope;
      this.location = location;
      this.key = key;
      this.execute = execute;
    }

    String verb() {
      return verb;
    }

    String location() {
      return location;
    }

    String key() {
      return key;
    }

    List<Call> resolve(List<String> inconsistencies) {
      Set<String> templates = templates(uri, scope);
      if (execute != null) {
        ExpressionTree declaredVerb = execute.getArguments().get(0);
        Set<String> declared = templates(execute.getArguments().get(1), scope);
        String declaredName =
            declaredVerb instanceof MemberSelectTree select
                ? select.getIdentifier().toString()
                : declaredVerb.toString();
        if (!declaredName.equals(verb) || !declared.equals(templates)) {
          inconsistencies.add(
              key
                  + " at "
                  + location
                  + ": declares "
                  + declaredName
                  + " "
                  + declared.stream().map(BackendCallScanner::display).toList()
                  + " but sends "
                  + verb
                  + " "
                  + templates.stream().map(BackendCallScanner::display).toList());
        }
      }
      return templates.stream().map(t -> new Call(verb, t, location, key)).toList();
    }
  }

  private Set<String> templates(ExpressionTree expression, Scope scope) {
    Set<String> templates = new LinkedHashSet<>();
    for (String raw : resolve(expression, scope)) {
      String template = canonical(raw);
      if (isResolved(template)) {
        templates.add(template);
      }
    }
    return templates;
  }

  private @Nullable Site siteOf(
      TreePath path, CompilationUnitTree unit, MethodInvocationTree node) {
    if (!(node.getMethodSelect() instanceof MemberSelectTree select)) {
      return null;
    }
    String name = select.getIdentifier().toString();
    TypeInfo type = enclosingType(path);
    if (type == null) {
      return null;
    }
    MethodTree method = enclosingMethod(path);
    Scope scope = new Scope(type, method, Map.of(), 0);
    String receiver = receiverName(select.getExpression());
    if ("backendApiClient".equals(receiver)) {
      if (VERB_METHODS.contains(name) && !node.getArguments().isEmpty()) {
        String verb = name.toUpperCase(Locale.ROOT);
        ExpressionTree uri = node.getArguments().get(0);
        return new Site(verb, uri, scope, location(unit, node), key(type, method, verb, uri), null);
      }
      if ("execute".equals(name) && node.getArguments().size() == 4) {
        return executeSite(unit, node, type, method, scope);
      }
      return null;
    }
    if ("uri".equals(name) && !node.getArguments().isEmpty()) {
      String root = chainRoot(select.getExpression());
      if (root != null && RAW_CLIENTS.contains(root)) {
        String verb = chainVerb(select.getExpression());
        ExpressionTree uri = node.getArguments().get(0);
        return new Site(verb, uri, scope, location(unit, node), key(type, method, verb, uri), null);
      }
    }
    return null;
  }

  private Site executeSite(
      CompilationUnitTree unit,
      MethodInvocationTree node,
      TypeInfo type,
      @Nullable MethodTree method,
      Scope scope) {
    ExpressionTree request = node.getArguments().get(2);
    String verb = "?";
    ExpressionTree uri = node.getArguments().get(1);
    Scope lambdaScope = scope;
    if (request instanceof LambdaExpressionTree lambda && lambda.getParameters().size() == 1) {
      String client = lambda.getParameters().get(0).getName().toString();
      MethodInvocationTree uriCall = findUriCall(lambda.getBody(), client);
      if (uriCall != null && uriCall.getMethodSelect() instanceof MemberSelectTree uriSelect) {
        verb = chainVerb(uriSelect.getExpression());
        ExpressionTree argument = uriCall.getArguments().get(0);
        if (argument instanceof LambdaExpressionTree builder
            && builder.getParameters().size() == 1
            && builder.getBody() instanceof ExpressionTree body) {
          Map<String, Set<String>> bindings = new HashMap<>();
          bindings.put(builder.getParameters().get(0).getName().toString(), Set.of(""));
          lambdaScope = new Scope(type, method, bindings, 0);
          uri = body;
        } else {
          uri = argument;
        }
      }
    }
    return new Site(
        verb,
        uri,
        lambdaScope,
        location(unit, node),
        key(type, method, verb, node.getArguments().get(1)),
        node);
  }

  private static @Nullable MethodInvocationTree findUriCall(Tree body, String client) {
    MethodInvocationTree[] found = {null};
    new TreeScanner<Void, Void>() {
      @Override
      public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
        if (found[0] == null
            && node.getMethodSelect() instanceof MemberSelectTree select
            && "uri".equals(select.getIdentifier().toString())
            && !node.getArguments().isEmpty()
            && client.equals(chainRoot(select.getExpression()))) {
          found[0] = node;
        }
        return super.visitMethodInvocation(node, unused);
      }
    }.scan(body, null);
    return found[0];
  }

  private static @Nullable String receiverName(ExpressionTree expression) {
    if (expression instanceof IdentifierTree identifier) {
      return identifier.getName().toString();
    }
    if (expression instanceof MemberSelectTree select
        && select.getExpression() instanceof IdentifierTree owner
        && "this".contentEquals(owner.getName())) {
      return select.getIdentifier().toString();
    }
    return null;
  }

  private static @Nullable String chainRoot(ExpressionTree expression) {
    ExpressionTree current = expression;
    while (true) {
      if (current instanceof MethodInvocationTree invocation
          && invocation.getMethodSelect() instanceof MemberSelectTree select) {
        current = select.getExpression();
      } else {
        return receiverName(current);
      }
    }
  }

  private static String chainVerb(ExpressionTree expression) {
    String verb = "?";
    ExpressionTree current = expression;
    while (current instanceof MethodInvocationTree invocation
        && invocation.getMethodSelect() instanceof MemberSelectTree select) {
      String name = select.getIdentifier().toString();
      if (VERB_METHODS.contains(name) && invocation.getArguments().isEmpty()) {
        verb = name.toUpperCase(Locale.ROOT);
      } else if ("method".equals(name)
          && invocation.getArguments().size() == 1
          && invocation.getArguments().get(0) instanceof MemberSelectTree method) {
        verb = method.getIdentifier().toString();
      }
      current = select.getExpression();
    }
    return verb;
  }

  private String location(CompilationUnitTree unit, Tree node) {
    long line = unit.getLineMap().getLineNumber(positions.getStartPosition(unit, node));
    String name = unit.getSourceFile().getName().replace('\\', '/');
    String file = name.substring(name.lastIndexOf('/') + 1);
    return file + ":" + line;
  }

  private static String key(
      TypeInfo type, @Nullable MethodTree method, String verb, ExpressionTree uri) {
    String owner = method == null ? "<init>" : method.getName().toString();
    return type.name() + "#" + owner + " " + verb + " " + uri;
  }

  private @Nullable TypeInfo enclosingType(TreePath path) {
    for (TreePath p = path; p != null; p = p.getParentPath()) {
      if (p.getLeaf() instanceof ClassTree classTree) {
        return typesByTree.get(classTree);
      }
    }
    return null;
  }

  private static @Nullable MethodTree enclosingMethod(TreePath path) {
    for (TreePath p = path; p != null; p = p.getParentPath()) {
      if (p.getLeaf() instanceof MethodTree method) {
        return method;
      }
      if (p.getLeaf() instanceof ClassTree) {
        return null;
      }
    }
    return null;
  }

  private Set<String> resolve(ExpressionTree expression, Scope scope) {
    if (scope.depth() > MAX_DEPTH) {
      return Set.of();
    }
    return switch (expression) {
      case LiteralTree literal ->
          literal.getValue() == null ? Set.of() : Set.of(String.valueOf(literal.getValue()));
      case ParenthesizedTree parenthesized -> resolve(parenthesized.getExpression(), scope);
      case TypeCastTree cast -> resolve(cast.getExpression(), scope);
      case ConditionalExpressionTree conditional -> {
        Set<String> both = new LinkedHashSet<>(resolve(conditional.getTrueExpression(), scope));
        both.addAll(resolve(conditional.getFalseExpression(), scope));
        yield both;
      }
      case BinaryTree binary when binary.getKind() == Tree.Kind.PLUS ->
          concat(
              orDynamic(resolve(binary.getLeftOperand(), scope)),
              orDynamic(resolve(binary.getRightOperand(), scope)));
      case IdentifierTree identifier -> resolveName(identifier.getName().toString(), scope);
      case MemberSelectTree select -> resolveMember(select, scope);
      case MethodInvocationTree invocation -> resolveInvocation(invocation, scope);
      case NewClassTree creation when isTextual(creation.getIdentifier()) ->
          creation.getArguments().isEmpty()
              ? Set.of("")
              : resolve(creation.getArguments().get(0), scope.deeper());
      default -> Set.of();
    };
  }

  private static Set<String> orDynamic(Set<String> values) {
    return values.isEmpty() ? Set.of(String.valueOf(DYNAMIC)) : values;
  }

  private static Set<String> concat(Set<String> left, Set<String> right) {
    Set<String> joined = new LinkedHashSet<>();
    for (String l : left) {
      for (String r : right) {
        if (joined.size() < MAX_ALTERNATIVES) {
          joined.add(l + r);
        }
      }
    }
    return joined;
  }

  private Set<String> resolveName(String name, Scope scope) {
    Set<String> bound = scope.bindings().get(name);
    if (bound != null) {
      return bound;
    }
    if (scope.method() != null) {
      Set<String> local = resolveLocal(name, scope);
      if (local != null) {
        return local;
      }
      for (int i = 0; i < scope.method().getParameters().size(); i++) {
        VariableTree parameter = scope.method().getParameters().get(i);
        if (parameter.getName().contentEquals(name)) {
          return isTextual(parameter.getType())
              ? resolveParameter(scope.type(), scope.method(), i, scope.depth())
              : Set.of();
        }
      }
    }
    for (TypeInfo type = scope.type(); type != null; type = type.outer()) {
      Set<String> field = resolveField(type, name, scope.depth());
      if (field != null) {
        return field;
      }
    }
    TypeInfo imported = staticImportOwner(scope.type().unit(), name);
    if (imported != null) {
      Set<String> field = resolveField(imported, name, scope.depth());
      if (field != null) {
        return field;
      }
    }
    return Set.of();
  }

  private @Nullable Set<String> resolveLocal(String name, Scope scope) {
    MethodTree method = scope.method();
    if (method == null || method.getBody() == null) {
      return null;
    }
    List<ExpressionTree> values = new ArrayList<>();
    List<MethodInvocationTree> extensions = new ArrayList<>();
    List<ExpressionTree> appended = new ArrayList<>();
    boolean[] declared = {false};
    boolean[] textual = {true};
    new TreeScanner<Void, Void>() {
      @Override
      public Void visitVariable(VariableTree node, Void unused) {
        if (node.getName().contentEquals(name)) {
          declared[0] = true;
          textual[0] &= isTextual(node.getType());
          if (node.getInitializer() != null) {
            values.add(node.getInitializer());
          }
        }
        return super.visitVariable(node, unused);
      }

      @Override
      public Void visitAssignment(AssignmentTree node, Void unused) {
        if (node.getVariable() instanceof IdentifierTree target
            && target.getName().contentEquals(name)) {
          values.add(node.getExpression());
        }
        return super.visitAssignment(node, unused);
      }

      @Override
      public Void visitCompoundAssignment(CompoundAssignmentTree node, Void unused) {
        if (node.getVariable() instanceof IdentifierTree target
            && target.getName().contentEquals(name)) {
          appended.add(node.getExpression());
        }
        return super.visitCompoundAssignment(node, unused);
      }

      @Override
      public Void visitExpressionStatement(ExpressionStatementTree node, Void unused) {
        if (node.getExpression() instanceof MethodInvocationTree invocation
            && name.equals(chainRoot(invocation))) {
          List<MethodInvocationTree> chain = new ArrayList<>();
          ExpressionTree current = invocation;
          while (current instanceof MethodInvocationTree link
              && link.getMethodSelect() instanceof MemberSelectTree select) {
            String method = select.getIdentifier().toString();
            if (APPENDING.contains(method) || "pathSegment".equals(method)) {
              chain.addFirst(link);
            }
            current = select.getExpression();
          }
          extensions.addAll(chain);
        }
        return super.visitExpressionStatement(node, unused);
      }
    }.scan(method.getBody(), null);
    if (!declared[0]) {
      return null;
    }
    if (!textual[0]) {
      return Set.of();
    }
    Set<String> resolved = new LinkedHashSet<>();
    for (ExpressionTree value : values) {
      resolved.addAll(resolve(value, scope.deeper()));
    }
    for (MethodInvocationTree extension : extensions) {
      String extender = ((MemberSelectTree) extension.getMethodSelect()).getIdentifier().toString();
      resolved = extend(resolved, extender, extension.getArguments(), scope);
    }
    for (ExpressionTree suffix : appended) {
      resolved = concat(resolved, orDynamic(resolve(suffix, scope.deeper())));
    }
    return resolved;
  }

  private Set<String> resolveParameter(TypeInfo type, MethodTree method, int index, int depth) {
    TypeInfo top = type;
    while (top.outer() != null) {
      top = top.outer();
    }
    String name = method.getName().toString();
    int arity = method.getParameters().size();
    Set<String> resolved = new LinkedHashSet<>();
    CompilationUnitTree unit = top.unit();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
        ExpressionTree select = node.getMethodSelect();
        boolean sameClass =
            select instanceof IdentifierTree id && id.getName().contentEquals(name)
                || select instanceof MemberSelectTree member
                    && member.getIdentifier().contentEquals(name)
                    && isThis(member.getExpression());
        if (sameClass && node.getArguments().size() == arity) {
          TypeInfo callerType = enclosingType(getCurrentPath());
          if (callerType != null) {
            Scope caller =
                new Scope(callerType, enclosingMethod(getCurrentPath()), Map.of(), depth + 1);
            resolved.addAll(resolve(node.getArguments().get(index), caller));
          }
        }
        return super.visitMethodInvocation(node, unused);
      }
    }.scan(unit, null);
    return resolved;
  }

  private @Nullable Set<String> resolveField(TypeInfo type, String name, int depth) {
    for (Tree member : type.tree().getMembers()) {
      if (member instanceof VariableTree field && field.getName().contentEquals(name)) {
        if (field.getInitializer() == null || !isTextual(field.getType())) {
          return Set.of();
        }
        return resolve(field.getInitializer(), new Scope(type, null, Map.of(), depth + 1));
      }
    }
    return null;
  }

  private @Nullable TypeInfo staticImportOwner(CompilationUnitTree unit, String name) {
    for (ImportTree importTree : unit.getImports()) {
      if (importTree.isStatic()
          && importTree.getQualifiedIdentifier() instanceof MemberSelectTree select
          && select.getIdentifier().contentEquals(name)) {
        return typeNamed(lastName(select.getExpression()));
      }
    }
    return null;
  }

  private Set<String> resolveMember(MemberSelectTree select, Scope scope) {
    String name = select.getIdentifier().toString();
    if (isThis(select.getExpression())) {
      for (TypeInfo type = scope.type(); type != null; type = type.outer()) {
        Set<String> field = resolveField(type, name, scope.depth());
        if (field != null) {
          return field;
        }
      }
      return Set.of();
    }
    TypeInfo owner = typeNamed(lastName(select.getExpression()));
    if (owner == null) {
      return Set.of();
    }
    Set<String> field = resolveField(owner, name, scope.depth());
    return field == null ? Set.of() : field;
  }

  private Set<String> resolveInvocation(MethodInvocationTree invocation, Scope scope) {
    List<? extends ExpressionTree> args = invocation.getArguments();
    if (invocation.getMethodSelect() instanceof IdentifierTree identifier) {
      return resolveHelper(scope.type(), identifier.getName().toString(), args, scope);
    }
    if (!(invocation.getMethodSelect() instanceof MemberSelectTree select)) {
      return Set.of();
    }
    String name = select.getIdentifier().toString();
    ExpressionTree receiver = select.getExpression();
    String receiverType = lastName(receiver);
    if ("String".equals(receiverType)) {
      if ("format".equals(name) && !args.isEmpty()) {
        return format(resolve(args.get(0), scope.deeper()), args.subList(1, args.size()), scope);
      }
      return Set.of();
    }
    if ("UriComponentsBuilder".equals(receiverType)) {
      if (BUILDER_ROOTS.contains(name) && !args.isEmpty()) {
        return resolve(args.get(0), scope.deeper());
      }
      return "newInstance".equals(name) ? Set.of("") : Set.of();
    }
    if ("formatted".equals(name)) {
      return format(resolve(receiver, scope.deeper()), args, scope);
    }
    if ("concat".equals(name) && args.size() == 1) {
      return concat(
          orDynamic(resolve(receiver, scope.deeper())),
          orDynamic(resolve(args.get(0), scope.deeper())));
    }
    if (APPENDING.contains(name) && args.size() == 1 || "pathSegment".equals(name)) {
      Set<String> base = resolve(receiver, scope.deeper());
      return base.isEmpty() ? Set.of() : extend(base, name, args, scope);
    }
    if (PATH_PRESERVING.contains(name)) {
      return resolve(receiver, scope.deeper());
    }
    if (args.isEmpty() && receiver instanceof MemberSelectTree constant) {
      Set<String> property = resolveEnumProperty(constant, name, scope);
      if (property != null) {
        return property;
      }
    }
    TypeInfo owner = typeNamed(receiverType);
    if (owner != null && receiver instanceof IdentifierTree && isTypeName(receiverType)) {
      return resolveHelper(owner, name, args, scope);
    }
    if ("this".equals(receiverType)) {
      return resolveHelper(scope.type(), name, args, scope);
    }
    return Set.of();
  }

  private Set<String> extend(
      Set<String> base, String method, List<? extends ExpressionTree> args, Scope scope) {
    Set<String> result = base;
    for (ExpressionTree argument : args) {
      Set<String> part = orDynamic(resolve(argument, scope.deeper()));
      result =
          "pathSegment".equals(method)
              ? concat(concat(result, Set.of("/")), part)
              : concat(result, part);
    }
    return result;
  }

  private @Nullable Set<String> resolveEnumProperty(
      MemberSelectTree constant, String getter, Scope scope) {
    TypeInfo owner = typeNamed(lastName(constant.getExpression()));
    if (owner == null || owner.tree().getKind() != Tree.Kind.ENUM) {
      return null;
    }
    String field =
        getter.startsWith("get") && getter.length() > 3
            ? Character.toLowerCase(getter.charAt(3)) + getter.substring(4)
            : getter;
    List<String> fields = new ArrayList<>();
    NewClassTree creation = null;
    for (Tree member : owner.tree().getMembers()) {
      if (member instanceof VariableTree variable) {
        if (variable.getName().contentEquals(constant.getIdentifier())
            && variable.getInitializer() instanceof NewClassTree init) {
          creation = init;
        } else if (variable.getInitializer() == null
            && !variable.getModifiers().getFlags().contains(Modifier.STATIC)) {
          fields.add(variable.getName().toString());
        }
      }
    }
    int index = fields.indexOf(field);
    if (creation == null || index < 0 || index >= creation.getArguments().size()) {
      return null;
    }
    for (Tree member : owner.tree().getMembers()) {
      if (member instanceof MethodTree constructor
          && "<init>".contentEquals(constructor.getName())
          && constructor.getParameters().size() == creation.getArguments().size()) {
        for (int i = 0; i < constructor.getParameters().size(); i++) {
          if (constructor.getParameters().get(i).getName().contentEquals(field)) {
            index = i;
          }
        }
      }
    }
    return resolve(
        creation.getArguments().get(index), new Scope(owner, null, Map.of(), scope.depth() + 1));
  }

  private Set<String> resolveHelper(
      TypeInfo type, String name, List<? extends ExpressionTree> args, Scope scope) {
    for (TypeInfo current = type; current != null; current = current.outer()) {
      Set<String> resolved = new LinkedHashSet<>();
      boolean found = false;
      for (Tree member : current.tree().getMembers()) {
        if (member instanceof MethodTree method
            && method.getName().contentEquals(name)
            && method.getParameters().size() == args.size()
            && method.getBody() != null
            && isTextual(method.getReturnType())) {
          found = true;
          Map<String, Set<String>> bindings = new HashMap<>();
          for (int i = 0; i < args.size(); i++) {
            VariableTree parameter = method.getParameters().get(i);
            bindings.put(
                parameter.getName().toString(),
                isTextual(parameter.getType())
                    ? orDynamic(resolve(args.get(i), scope.deeper()))
                    : Set.of(String.valueOf(DYNAMIC)));
          }
          Scope inner = new Scope(current, method, bindings, scope.depth() + 1);
          for (ExpressionTree returned : returns(method)) {
            resolved.addAll(resolve(returned, inner));
          }
        }
      }
      if (found) {
        return resolved;
      }
    }
    return Set.of();
  }

  private static List<ExpressionTree> returns(MethodTree method) {
    List<ExpressionTree> returned = new ArrayList<>();
    new TreeScanner<Void, Void>() {
      @Override
      public Void visitReturn(ReturnTree node, Void unused) {
        if (node.getExpression() != null) {
          returned.add(node.getExpression());
        }
        return null;
      }

      @Override
      public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
        return null;
      }

      @Override
      public Void visitClass(ClassTree node, Void unused) {
        return null;
      }
    }.scan(method.getBody(), null);
    return returned;
  }

  private Set<String> format(
      Set<String> patterns, List<? extends ExpressionTree> args, Scope scope) {
    List<Set<String>> values = new ArrayList<>();
    for (ExpressionTree argument : args) {
      values.add(orDynamic(resolve(argument, scope.deeper())));
    }
    Set<String> formatted = new LinkedHashSet<>();
    for (String pattern : patterns) {
      Set<String> results = Set.of("");
      Matcher matcher = FORMAT_CONVERSION.matcher(pattern);
      int last = 0;
      int next = 0;
      while (matcher.find()) {
        results = concat(results, Set.of(pattern.substring(last, matcher.start())));
        last = matcher.end();
        String conversion = matcher.group(2);
        if ("%".equals(conversion)) {
          results = concat(results, Set.of("%"));
        } else if ("n".equals(conversion)) {
          results = concat(results, Set.of("\n"));
        } else {
          String explicit = matcher.group(1);
          int index = explicit != null ? argumentIndex(explicit) : next++;
          results =
              concat(
                  results,
                  index >= 0 && index < values.size()
                      ? values.get(index)
                      : Set.of(String.valueOf(DYNAMIC)));
        }
      }
      formatted.addAll(concat(results, Set.of(pattern.substring(last))));
    }
    return formatted;
  }

  /**
   * Converts the digits of an explicit {@code %n$} format index into a zero-based argument index.
   *
   * @param digits the one-based index as written in the pattern, digits only
   * @return the zero-based index, or {@code -1} when the number does not fit an {@code int}, which
   *     the caller treats like any other index without an argument
   */
  static int argumentIndex(@NotNull String digits) {
    try {
      return Integer.parseInt(digits) - 1;
    } catch (NumberFormatException ignored) {
      return -1;
    }
  }

  private static boolean isTextual(@Nullable Tree type) {
    if (type == null) {
      return true;
    }
    String name = type.toString();
    return TEXTUAL_TYPES.contains(name.substring(name.lastIndexOf('.') + 1));
  }

  private static boolean isThis(ExpressionTree expression) {
    return expression instanceof IdentifierTree identifier
        && "this".contentEquals(identifier.getName());
  }

  private static boolean isTypeName(@Nullable String name) {
    return name != null && !name.isEmpty() && Character.isUpperCase(name.charAt(0));
  }

  private @Nullable TypeInfo typeNamed(@Nullable String name) {
    if (name == null) {
      return null;
    }
    List<TypeInfo> candidates = typesByName.get(name);
    return candidates != null && candidates.size() == 1 ? candidates.get(0) : null;
  }

  private static @Nullable String lastName(ExpressionTree expression) {
    if (expression instanceof IdentifierTree identifier) {
      return identifier.getName().toString();
    }
    if (expression instanceof MemberSelectTree select) {
      return select.getIdentifier().toString();
    }
    return null;
  }

  /** A Java source held in memory. */
  private static final class InMemorySource extends SimpleJavaFileObject {
    private final String text;

    private InMemorySource(String name, String text) {
      super(URI.create("string:///" + name), Kind.SOURCE);
      this.text = text;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return text;
    }
  }
}
