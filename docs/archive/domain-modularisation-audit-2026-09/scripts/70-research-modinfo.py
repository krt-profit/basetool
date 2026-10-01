"""Report whether Maven Central jars ship a module-info.class or only an Automatic-Module-Name.

Usage: python 70-research-modinfo.py
Downloads each jar into 70-research-jars/ (skipped when already present).
"""
import pathlib
import sys
import urllib.request
import zipfile

CENTRAL = "https://repo1.maven.org/maven2"
JARS = [
    ("org.springframework", "spring-core", "7.0.9"),
    ("org.springframework", "spring-context", "7.0.9"),
    ("org.springframework", "spring-webmvc", "7.0.9"),
    ("org.springframework.boot", "spring-boot", "4.1.1"),
    ("org.springframework.boot", "spring-boot-autoconfigure", "4.1.1"),
    ("org.springframework.security", "spring-security-core", "7.1.1"),
    ("org.springframework.data", "spring-data-jpa", "4.1.1"),
    ("org.springframework.modulith", "spring-modulith-core", "2.1.1"),
    ("org.hibernate.orm", "hibernate-core", "7.4.5.Final"),
    ("tools.jackson.core", "jackson-databind", "3.1.5"),
    ("org.jspecify", "jspecify", "1.0.1"),
]

def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    out = pathlib.Path(__file__).parent / "70-research-jars"
    out.mkdir(exist_ok=True)
    print("| artifact | module-info.class | Automatic-Module-Name |")
    print("| --- | --- | --- |")
    for g, a, v in JARS:
        name = f"{a}-{v}.jar"
        path = out / name
        if not path.exists():
            url = f"{CENTRAL}/{g.replace('.', '/')}/{a}/{v}/{name}"
            with urllib.request.urlopen(url, timeout=120) as r:
                path.write_bytes(r.read())
        z = zipfile.ZipFile(path)
        mi = [n for n in z.namelist() if n.endswith("module-info.class")]
        manifest = z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace").splitlines()
        amn = next((l.split(":", 1)[1].strip() for l in manifest
                    if l.startswith("Automatic-Module-Name")), "-")
        print(f"| {g}:{a}:{v} | {', '.join(mi) if mi else 'none'} | {amn} |")

if __name__ == "__main__":
    main()
