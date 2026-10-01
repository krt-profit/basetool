"""Extracts the Spring sources needed for claims 3 and 4 into 95-verify-4-src."""
import glob
import os
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "95-verify-4-src")
CACHE = os.path.expanduser(r"~\.gradle\caches\modules-2\files-2.1")
JARS = {
    "spring-web": (os.path.join(CACHE, "org.springframework", "spring-web", "7.0.9", "*", "spring-web-7.0.9-sources.jar"), ("org/springframework/web/service/",)),
    "spring-webflux": (os.path.join(CACHE, "org.springframework", "spring-webflux", "7.0.9", "*", "spring-webflux-7.0.9-sources.jar"), ("org/springframework/web/reactive/function/client/",)),
}
for name, (pattern, prefixes) in JARS.items():
    for jar in glob.glob(pattern):
        with zipfile.ZipFile(jar) as z:
            n = 0
            for e in z.namelist():
                if e.endswith(".java") and any(e.startswith(p) for p in prefixes):
                    z.extract(e, OUT)
                    n += 1
            print(name, jar, n)
