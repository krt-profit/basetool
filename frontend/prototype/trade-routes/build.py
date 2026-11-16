"""Builds standalone.html: the prototype with the design system, Lato and the icon sprite inlined."""

import base64
import pathlib
import re

HERE = pathlib.Path(__file__).resolve().parent
DS = HERE.parents[2] / ".claude" / "skills" / "das-kartell-design"
SCRIPTS = [
    "data.js",
    "surfaces-data.js",
    "edit-data.js",
    "long-data.js",
    "build-data.js",
    "model.js",
    "edit-engine.js",
    "build-engine.js",
    "fragments.js",
    "forms.js",
    "variants.js",
    "surfaces-route.js",
    "surfaces-lists.js",
    "surfaces-mission.js",
    "surfaces-ratings.js",
    "surfaces-admin.js",
    "edit-view.js",
    "long-view.js",
    "build-view.js",
    "flight.js",
    "surfaces-app.js",
    "edit-app.js",
    "long-app.js",
    "build-app.js",
    "overlays.js",
    "app.js",
]
WEIGHTS = {300: "Lato-Light", 400: "Lato-Regular", 700: "Lato-Bold", 900: "Lato-Black"}


def fonts() -> str:
    """Returns @font-face rules for the used Lato weights as data URIs."""
    rules = []
    for weight, name in WEIGHTS.items():
        data = base64.b64encode((DS / "fonts" / f"{name}.woff2").read_bytes()).decode("ascii")
        rules.append(
            "@font-face { font-family: 'Lato'; "
            f"src: url(data:font/woff2;base64,{data}) format('woff2'); "
            f"font-weight: {weight}; font-style: normal; font-display: swap; }}"
        )
    return "\n".join(rules)


def css() -> str:
    """Returns the tokens without their font files, the component bundle and the prototype styles."""
    tokens = re.sub(r"@font-face\s*\{[^}]*\}", "", (DS / "tokens.css").read_text(encoding="utf-8"))
    bundle = (DS / "components" / "bundle.css").read_text(encoding="utf-8")
    own = (HERE / "prototype.css").read_text(encoding="utf-8")
    return "\n".join([fonts(), tokens, bundle, own])


def sprite() -> str:
    """Returns the icon sprite, already hidden by its own root element."""
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
