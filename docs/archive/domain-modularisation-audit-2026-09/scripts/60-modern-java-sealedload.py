"""JVM-level probe: can a class that is not in a sealed class's PermittedSubclasses be loaded?

Mimics a runtime-generated proxy (Hibernate/ByteBuddy style): Proxy is compiled against an
unsealed Base, then run against the sealed Base.
"""

import os
import shutil
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.path.join(HERE, "60-modern-java-probe", "sealedload")
JDK = r"C:\Program Files\Zulu\zulu-25\bin"

SRC = {
    "open/p/Base.java": "package p;\npublic abstract class Base {}\n",
    "open/p/Proxy.java": "package p;\npublic class Proxy extends Base {}\n",
    "sealed/p/Base.java": "package p;\npublic abstract sealed class Base permits Real {}\n",
    "sealed/p/Real.java": "package p;\npublic final class Real extends Base {}\n",
    "main/Main.java": "public class Main { public static void main(String[] a) throws Exception { "
                      "try { Class.forName(\"p.Proxy\"); System.out.println(\"LOADED\"); } "
                      "catch (Throwable t) { System.out.println(t); } } }\n",
}

def run(cmd):
    p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return (p.stdout + p.stderr).strip()

if os.path.isdir(BASE):
    shutil.rmtree(BASE)
for rel, text in SRC.items():
    path = os.path.join(BASE, *rel.split("/"))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)
javac = os.path.join(JDK, "javac.exe")
java = os.path.join(JDK, "java.exe")
o_open = os.path.join(BASE, "out-open")
o_sealed = os.path.join(BASE, "out-sealed")
o_main = os.path.join(BASE, "out-main")
print(run([javac, "-d", o_open, os.path.join(BASE, "open", "p", "Base.java"), os.path.join(BASE, "open", "p", "Proxy.java")]))
print(run([javac, "-d", o_sealed, os.path.join(BASE, "sealed", "p", "Base.java"), os.path.join(BASE, "sealed", "p", "Real.java")]))
print(run([javac, "-d", o_main, os.path.join(BASE, "main", "Main.java")]))
os.makedirs(os.path.join(o_sealed, "p"), exist_ok=True)
shutil.copy(os.path.join(o_open, "p", "Proxy.class"), os.path.join(o_sealed, "p", "Proxy.class"))
print("run against unsealed Base:", run([java, "-cp", o_open + os.pathsep + o_main, "Main"]))
print("run against sealed Base:  ", run([java, "-cp", o_sealed + os.pathsep + o_main, "Main"]))
