"""Tool probes: javac 25 (release 25 and --release 21), Checkstyle 14.3.0 with the repo's
google_checks.xml, and google-java-format 1.36.1, run on small throwaway sources.

Everything is written below 60-modern-java-probe/ in the scratchpad; nothing in the repository
is touched. Output: 60-modern-java-probes-out.txt.
"""

import glob
import os
import shutil
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.path.join(HERE, "60-modern-java-probe")
REPO = r"$REPO"
JDK = r"C:\Program Files\Zulu\zulu-25\bin"
CACHE = os.path.join(os.path.expanduser("~"), ".gradle", "caches", "modules-2", "files-2.1")

def jar(group, artifact, version):
    hits = glob.glob(os.path.join(CACHE, group, artifact, version, "*", f"{artifact}-{version}.jar"))
    if not hits:
        raise SystemExit(f"missing {group}:{artifact}:{version}")
    return hits[0]

CS_CP = os.pathsep.join([
    jar("com.puppycrawl.tools", "checkstyle", "14.3.0"),
    jar("info.picocli", "picocli", "4.7.7"),
    jar("org.antlr", "antlr4-runtime", "4.13.2"),
    jar("commons-beanutils", "commons-beanutils", "1.11.0"),
    jar("commons-logging", "commons-logging", "1.3.6"),
    jar("com.google.guava", "guava", "33.7.1-jre"),
    jar("com.google.guava", "failureaccess", "1.0.3"),
    jar("org.reflections", "reflections", "0.10.2"),
    jar("org.javassist", "javassist", "3.28.0-GA"),
    jar("net.sf.saxon", "Saxon-HE", "12.10"),
    jar("org.xmlresolver", "xmlresolver", "5.3.3"),
    jar("org.slf4j", "slf4j-api", "2.0.18"),
    jar("commons-collections", "commons-collections", "3.2.2"),
])
GJF_CP = os.pathsep.join([
    jar("com.google.googlejavaformat", "google-java-format", "1.36.1"),
    jar("com.google.guava", "guava", "33.7.1-jre"),
    jar("com.google.guava", "failureaccess", "1.0.3"),
    jar("org.commonmark", "commonmark", "0.28.0"),
    jar("org.commonmark", "commonmark-ext-gfm-tables", "0.28.0"),
])
GJF_EXPORTS = []
for pkg in ("api", "code", "file", "parser", "tree", "util"):
    GJF_EXPORTS += ["--add-exports", f"jdk.compiler/com.sun.tools.javac.{pkg}=ALL-UNNAMED"]

LICENSE = ""

SOURCES = {
    "a/UnnamedVars.java": '''package probe.a;

import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;

/** Probe for unnamed variables and patterns. */
public final class UnnamedVars {

  private UnnamedVars() {}

  /**
   * Sums the values of a map.
   *
   * @param map the map
   * @return the sum
   */
  public static int count(Map<String, Integer> map) {
    int[] n = {0};
    map.forEach((_, v) -> n[0] += v);
    for (var _ : List.of(1, 2)) {
      n[0]++;
    }
    try {
      Integer.parseInt("x");
    } catch (NumberFormatException _) {
      n[0]--;
    }
    return n[0];
  }

  /**
   * Swallows a parse failure.
   *
   * @param s the text
   */
  public static void swallow(String s) {
    try {
      Integer.parseInt(s);
    } catch (NumberFormatException _) {
    }
  }

  /**
   * Holds a lock for one call.
   *
   * @param l the lock
   * @throws Exception when unlocking fails
   */
  public static void locked(Lock l) throws Exception {
    l.lock();
    try (AutoCloseable _ = l::unlock) {
      l.hashCode();
    }
  }

  /**
   * Reads the first component of a pair.
   *
   * @param o the object
   * @return the key
   */
  public static String key(Object o) {
    if (o instanceof Pair(String k, _)) {
      return k;
    }
    return switch (o) {
      case Pair(var k, _) -> String.valueOf(k);
      default -> "";
    };
  }

  /**
   * A pair.
   *
   * @param a the first
   * @param b the second
   */
  public record Pair(Object a, Object b) {}
}
''',
    "b/EnumSwitches.java": '''package probe.b;

/** Probe for exhaustive enum switch statements. */
public final class EnumSwitches {

  private EnumSwitches() {}

  /** Colours. */
  public enum Colour {
    /** Red. */
    RED,
    /** Green. */
    GREEN,
    /** Blue. */
    BLUE
  }

  /**
   * Maps a colour with an arrow statement and no default.
   *
   * @param c the colour
   * @return a code
   */
  public static int plainNoDefault(Colour c) {
    int r = 0;
    switch (c) {
      case RED -> r = 1;
      case GREEN -> r = 2;
      case BLUE -> r = 3;
    }
    return r;
  }

  /**
   * Maps a colour with a null-labelled statement and no default.
   *
   * @param c the colour
   * @return a code
   */
  public static int nullLabelNoDefault(Colour c) {
    int r = 0;
    switch (c) {
      case null -> throw new IllegalArgumentException("null");
      case RED -> r = 1;
      case GREEN -> r = 2;
      case BLUE -> r = 3;
    }
    return r;
  }

  /**
   * Maps a colour with a switch expression and no default.
   *
   * @param c the colour
   * @return a code
   */
  public static int exprNoDefault(Colour c) {
    return switch (c) {
      case RED -> 1;
      case GREEN -> 2;
      case BLUE -> 3;
    };
  }
}
''',
    "b2/EnumSwitchesMissing.java": '''package probe.b2;

/** Probe: one constant is not handled. */
public final class EnumSwitchesMissing {

  private EnumSwitchesMissing() {}

  /** Colours. */
  public enum Colour {
    /** Red. */
    RED,
    /** Green. */
    GREEN,
    /** Blue. */
    BLUE
  }

  /**
   * Arrow statement, no default, BLUE missing.
   *
   * @param c the colour
   * @return a code
   */
  public static int plainMissing(Colour c) {
    int r = 0;
    switch (c) {
      case RED -> r = 1;
      case GREEN -> r = 2;
    }
    return r;
  }

  /**
   * Null-labelled statement, no default, BLUE missing.
   *
   * @param c the colour
   * @return a code
   */
  public static int nullLabelMissing(Colour c) {
    int r = 0;
    switch (c) {
      case null -> throw new IllegalArgumentException("null");
      case RED -> r = 1;
      case GREEN -> r = 2;
    }
    return r;
  }

  /**
   * Expression, no default, BLUE missing.
   *
   * @param c the colour
   * @return a code
   */
  public static int exprMissing(Colour c) {
    return switch (c) {
      case RED -> 1;
      case GREEN -> 2;
    };
  }
}
''',
    "c/MarkdownDoc.java": '''package probe.c;

/// Probe for Markdown documentation comments.
///
/// A second paragraph with `code`.
public final class MarkdownDoc {

  private MarkdownDoc() {}

  /// Returns the input plus forty-two.
  ///
  /// @param x the input
  /// @return the sum
  public static int answer(int x) {
    return x + 42;
  }
}
''',
    "d/FlexibleCtor.java": '''package probe.d;

/** Probe for statements before super. */
public class FlexibleCtor extends IllegalStateException {

  /**
   * Creates the exception.
   *
   * @param code the code
   */
  public FlexibleCtor(String code) {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("code");
    }
    String message = "failed: " + code;
    super(message);
  }
}
''',
    "e1/Gather.java": '''package probe.e1;

import java.util.List;
import java.util.stream.Gatherers;

/** Probe: stream gatherers (JEP 485). */
public final class Gather {

  private Gather() {}

  /**
   * Chunks a list.
   *
   * @param in the list
   * @return the chunks
   */
  public static List<List<Integer>> chunks(List<Integer> in) {
    return in.stream().gather(Gatherers.windowFixed(2)).toList();
  }
}
''',
    "e2/Scoped.java": '''package probe.e2;

/** Probe: scoped values (JEP 506). */
public final class Scoped {

  private Scoped() {}

  /** The value. */
  public static final ScopedValue<String> ID = ScopedValue.newInstance();

  /**
   * Runs with a bound value.
   *
   * @param r the task
   */
  public static void run(Runnable r) {
    ScopedValue.where(ID, "x").run(r);
  }
}
''',
    "e3/ClassFileApi.java": '''package probe.e3;

import java.lang.classfile.ClassFile;

/** Probe: class-file API (JEP 484). */
public final class ClassFileApi {

  private ClassFileApi() {}

  /**
   * Opens the API.
   *
   * @return the context
   */
  public static Object open() {
    return ClassFile.of();
  }
}
''',
    "e4/Foreign.java": '''package probe.e4;

import java.lang.foreign.Arena;

/** Probe: foreign memory (JEP 454). */
public final class Foreign {

  private Foreign() {}

  /**
   * Opens an arena.
   *
   * @return the arena
   */
  public static Arena open() {
    return Arena.ofConfined();
  }
}
''',
    "e5/Java21Ok.java": '''package probe.e5;

import java.util.ArrayList;
import java.util.List;

/** Probe: Java 21 features that --release 21 must accept. */
public final class Java21Ok {

  private Java21Ok() {}

  /** A point. @param x x @param y y */
  public record Point(int x, int y) {}

  /**
   * Uses sequenced collections, clamp, repeat and record patterns.
   *
   * @param o the object
   * @return a text
   */
  public static String use(Object o) {
    List<Integer> l = new ArrayList<>(List.of(1, 2, 3));
    int first = l.getFirst() + l.reversed().getFirst();
    int c = Math.clamp(first, 0, 10);
    StringBuilder sb = new StringBuilder().repeat("x", c);
    return switch (o) {
      case Point(int x, int y) when x > y -> sb.toString();
      case Point p -> String.valueOf(p.x());
      default -> "";
    };
  }
}
''',
    "e6/ModuleImport.java": '''package probe.e6;

import module java.base;

/** Probe: module import (JEP 511). */
public final class ModuleImport {

  private ModuleImport() {}

  /**
   * Builds a list.
   *
   * @return the list
   */
  public static List<String> list() {
    return new ArrayList<>();
  }
}
''',
    "e7/Kdf.java": '''package probe.e7;

import javax.crypto.KDF;

/** Probe: key derivation API (JEP 510). */
public final class Kdf {

  private Kdf() {}

  /**
   * Looks up HKDF.
   *
   * @return the KDF
   * @throws Exception when unsupported
   */
  public static KDF hkdf() throws Exception {
    return KDF.getInstance("HKDF-SHA256");
  }
}
''',
    "f/api/Shape.java": '''package probe.f.api;

import probe.f.impl.Circle;

/** Probe: a sealed interface permitting a class in another package. */
public sealed interface Shape permits Circle {}
''',
    "f/impl/Circle.java": '''package probe.f.impl;

import probe.f.api.Shape;

/** A circle. */
public final class Circle implements Shape {}
''',
    "g/EmptyCatchNamed.java": '''package probe.g;

/** Control: the repository's current empty-catch convention. */
public final class EmptyCatchNamed {

  private EmptyCatchNamed() {}

  /**
   * Swallows a parse failure.
   *
   * @param s the text
   */
  public static void swallow(String s) {
    try {
      Integer.parseInt(s);
    } catch (NumberFormatException ignored) {
    }
  }
}
''',
}

JAVAC_CASES = [
    ("a", ["a/UnnamedVars.java"]),
    ("b", ["b/EnumSwitches.java"]),
    ("b2", ["b2/EnumSwitchesMissing.java"]),
    ("c", ["c/MarkdownDoc.java"]),
    ("d", ["d/FlexibleCtor.java"]),
    ("e1", ["e1/Gather.java"]),
    ("e2", ["e2/Scoped.java"]),
    ("e3", ["e3/ClassFileApi.java"]),
    ("e4", ["e4/Foreign.java"]),
    ("e5", ["e5/Java21Ok.java"]),
    ("e6", ["e6/ModuleImport.java"]),
    ("e7", ["e7/Kdf.java"]),
    ("f", ["f/api/Shape.java", "f/impl/Circle.java"]),
    ("g", ["g/EmptyCatchNamed.java"]),
]

def run(cmd, cwd=None):
    p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", cwd=cwd)
    return p.returncode, (p.stdout + p.stderr).strip()

def main():
    if os.path.isdir(BASE):
        shutil.rmtree(BASE)
    src_root = os.path.join(BASE, "src")
    for rel, text in SOURCES.items():
        p = os.path.join(src_root, *rel.split("/"))
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
    out = []
    javac = os.path.join(JDK, "javac.exe")
    java = os.path.join(JDK, "java.exe")
    out.append("# javac -version: " + run([javac, "-version"])[1])
    for release in ("25", "21"):
        for name, files in JAVAC_CASES:
            dest = os.path.join(BASE, "classes-" + release, name)
            os.makedirs(dest, exist_ok=True)
            cmd = [javac, "-J-Duser.language=en", "-J-Duser.country=US", "--release", release, "-parameters", "-Xlint:unchecked", "-Xlint:deprecation", "-d", dest]
            cmd += [os.path.join(src_root, *f.split("/")) for f in files]
            rc, text = run(cmd)
            first = "\n    ".join(line.replace(src_root, "<probe>") for line in text.splitlines() if ("error:" in line or "warning:" in line))[:1500]
            out.append(f"## javac --release {release} {name}: rc={rc}\n    {first}")
    cs_files = [os.path.join(src_root, *f.split("/")) for f in
                ["a/UnnamedVars.java", "b/EnumSwitches.java", "c/MarkdownDoc.java", "d/FlexibleCtor.java", "e5/Java21Ok.java", "e6/ModuleImport.java", "g/EmptyCatchNamed.java"]]
    rc, text = run([java, "-cp", CS_CP, "com.puppycrawl.tools.checkstyle.Main", "-c",
                    os.path.join(REPO, "config", "checkstyle", "google_checks.xml")] + cs_files, cwd=BASE)
    out.append("## checkstyle 14.3.0 google_checks.xml: rc=%d\n%s" % (rc, text.replace(src_root, "<probe>")))
    rc, text = run([java, "-cp", CS_CP, "com.puppycrawl.tools.checkstyle.Main", "-c",
                    os.path.join(REPO, "config", "checkstyle", "javadoc_position.xml")] + cs_files, cwd=BASE)
    out.append("## checkstyle 14.3.0 javadoc_position.xml: rc=%d\n%s" % (rc, text.replace(src_root, "<probe>")))
    rc, text = run([java] + GJF_EXPORTS + ["-cp", GJF_CP, "com.google.googlejavaformat.java.Main", "--version"])
    out.append("## gjf version: " + text)
    for f in cs_files:
        rc, text = run([java] + GJF_EXPORTS + ["-cp", GJF_CP, "com.google.googlejavaformat.java.Main", f])
        with open(f, encoding="utf-8") as fh:
            orig = fh.read()
        verdict = "UNCHANGED" if text.strip() == orig.strip() else "CHANGED"
        detail = ""
        if verdict == "CHANGED":
            import difflib
            detail = "\n".join(list(difflib.unified_diff(orig.splitlines(), text.splitlines(), lineterm="", n=0))[:30])
        out.append(f"## gjf 1.36.1 {os.path.relpath(f, src_root)}: rc={rc} {verdict}\n{detail}")
    with open(os.path.join(HERE, "60-modern-java-probes-out.txt"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(out) + "\n")
    print("\n".join(out))

if __name__ == "__main__":
    main()
