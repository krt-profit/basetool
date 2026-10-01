"""Find which classes of a jar contain given byte strings (used to locate ArchUnit's failOnEmptyShould default)."""
import os
import sys
import zipfile

JAR_ROOT = os.path.expanduser(r"~/.gradle/caches/modules-2/files-2.1/com.tngtech.archunit/archunit/1.5.1")

def main():
    jar = None
    for dp, _, fs in os.walk(JAR_ROOT):
        for f in fs:
            if f == "archunit-1.5.1.jar":
                jar = os.path.join(dp, f)
    print("jar:", jar)
    needles = [b"archRule.failOnEmptyShould", b"failOnEmptyShould", b"FAIL_ON_EMPTY_SHOULD"]
    with zipfile.ZipFile(jar) as z:
        for n in z.namelist():
            if not n.endswith(".class"):
                continue
            data = z.read(n)
            hits = [x.decode() for x in needles if x in data]
            if hits:
                print(n, hits)

if __name__ == "__main__":
    sys.exit(main())
