"""Builds standalone.html: the loaner prototype with the app's stylesheets, Lato and the icon sprite inlined."""

import base64
import pathlib
import re

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[2]
DS = REPO / ".claude" / "skills" / "das-kartell-design"
APP_CSS = REPO / "frontend" / "src" / "main" / "resources" / "static" / "css"
STYLESHEETS = [
    "kernel/styles.css",
    "kernel/overflow.css",
    "hangar/hangar-ships.css",
    "pages/hangar/hangar.css",
    "pages/hangar/hangar-squadron.css",
    "pages/hangar/member-hangar.css",
    "pages/catalogue/ship-data.css",
    "pages/mission/mission-detail.css",
]
SCRIPTS = [
    "data.js",
    "ui.js",
    "page-hangar.js",
    "page-import.js",
    "page-unit.js",
    "page-mission.js",
    "page-matrix.js",
    "page-shipdata.js",
    "app.js",
]
WEIGHTS = {"Light": 300, "Regular": 400, "Bold": 700, "Black": 900}


def font_uri(name: str) -> str:
    """Returns a Lato WOFF2 file of the design system as a data URI."""
    data = base64.b64encode((DS / "fonts" / f"Lato-{name}.woff2").read_bytes()).decode("ascii")
    return f"data:font/woff2;base64,{data}"


def fonts() -> str:
    """Returns @font-face rules for the Lato weights the prototype uses."""
    return "\n".join(
        "@font-face { font-family: 'Lato'; "
        f"src: url({font_uri(name)}) format('woff2'); "
        f"font-weight: {weight}; font-style: normal; font-display: swap; }}"
        for name, weight in WEIGHTS.items()
    )


def css() -> str:
    """Returns the app's own stylesheets with inlined fonts, followed by the prototype styles."""
    parts = [fonts()]
    for name in STYLESHEETS:
        text = (APP_CSS / name).read_text(encoding="utf-8")
        text = re.sub(r"@font-face\s*\{[^}]*\}", "", text)
        parts.append(text)
    parts.append((HERE / "prototype.css").read_text(encoding="utf-8"))
    return "\n".join(parts)


def sprite() -> str:
    """Returns the design system's icon sprite, hidden by its own root element."""
    svg = (DS / "assets" / "krt-icons.svg").read_text(encoding="utf-8")
    return re.sub(r"<\?xml[^>]*\?>", "", svg).strip()


def main() -> None:
    """Assembles index.html with its parts into standalone.html."""
    page = (HERE / "index.html").read_text(encoding="utf-8")
    js = "\n".join((HERE / name).read_text(encoding="utf-8") for name in SCRIPTS)
    out = page.replace("{{CSS}}", css()).replace("{{SPRITE}}", sprite()).replace("{{JS}}", js)
    (HERE / "standalone.html").write_text(out, encoding="utf-8", newline="\n")
    print(f"standalone.html: {len(out) // 1024} KiB")


if __name__ == "__main__":
    main()
