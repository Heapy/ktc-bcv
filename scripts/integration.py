#!/usr/bin/env python3
"""Exercise the installed local plugin in a throwaway consumer project."""
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def run(project, *args, succeeds=True, diagnostic=None):
    wrapper = "kotlin.bat" if __import__("os").name == "nt" else "./kotlin"
    result = subprocess.run([wrapper, *args], cwd=project, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=600)
    if (result.returncode == 0) != succeeds:
        raise AssertionError(f"Unexpected exit {result.returncode}: {' '.join(args)}\n{result.stdout}")
    if diagnostic and diagnostic not in result.stdout:
        raise AssertionError(f"Missing diagnostic {diagnostic!r}:\n{result.stdout}")
    print(f"PASS: {' '.join(args)} ({'success' if succeeds else 'expected failure'})", flush=True)
    return result.stdout


def prepare(project, plugin):
    for name in ("kotlin", "kotlin.bat", "project.yaml"):
        shutil.copy2(ROOT / name, project / name)
    shutil.copytree(ROOT / "plugins", project / "plugins")
    shutil.copytree(ROOT / "templates", project / "templates")
    shutil.copytree(ROOT / "example" / "src", project / "example" / "src")
    shutil.copy2(ROOT / "example" / "module.yaml", project / "example" / "module.yaml")


(ROOT / "build").mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="bcv-integration-", dir=ROOT / "build") as temp:
    project = Path(temp)
    prepare(project, "bcv")
    baseline = project / "example/api/example.api"
    run(project, "check", "apiCheck", "-m", "example", succeeds=False,
        diagnostic="API baseline is missing")
    assert not baseline.exists(), "Check must never create a missing baseline"
    run(project, "do", "apiDump", "-m", "example")
    original = baseline.read_bytes()
    assert b"example/Greeter" in original and b"greet" in original
    assert b"implementationDetail" not in original, "Kotlin internal API leaked into dump"
    run(project, "check", "apiCheck", "-m", "example")
    source = project / "example/src/Greeter.kt"
    source.write_text(source.read_text().replace("fun greet(", "fun welcome("))
    run(project, "check", "apiCheck", "-m", "example", succeeds=False,
        diagnostic="Public JVM API changed")
    assert baseline.read_bytes() == original, "Check overwrote the baseline"
    run(project, "do", "apiDump", "-m", "example")
    assert b"welcome" in baseline.read_bytes() and baseline.read_bytes() != original
    run(project, "check", "apiCheck", "-m", "example")
    other = project / "example/src/Other.kt"
    other.write_text("""package unrelated
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class PublicApi
@PublicApi public class Marked
public class Other
""")
    module = project / "example/module.yaml"
    original_module = module.read_text()
    module.write_text(original_module + "\nplugins:\n  bcv:\n    publicPackages: [example]\n")
    run(project, "do", "apiDump", "-m", "example")
    assert b"example/Greeter" in baseline.read_bytes()
    assert b"unrelated/Other" not in baseline.read_bytes(), "Package inclusion was ignored"
    assert b"unrelated/Marked" not in baseline.read_bytes()
    module.write_text(original_module + "\nplugins:\n  bcv:\n    publicPackages: [example]\n"
                      "    publicMarkers: [unrelated.PublicApi]\n")
    run(project, "do", "apiDump", "-m", "example")
    assert b"example/Greeter" in baseline.read_bytes() and b"unrelated/Marked" in baseline.read_bytes()
    assert b"unrelated/Other" not in baseline.read_bytes(), "Mixed inclusion leaked unmarked API"
    inherited = project / "example/src/Visible.java"
    inherited.write_text("""package inherited;
public class Visible extends Hidden {}
class Hidden { public static String inheritedMethod() { return "inherited"; } }
""")
    module.write_text(original_module + "\nplugins:\n  bcv:\n    publicClasses: [inherited.Visible]\n")
    run(project, "do", "apiDump", "-m", "example")
    assert b"inherited/Visible" in baseline.read_bytes()
    assert b"inheritedMethod" in baseline.read_bytes(), "Inclusion lost inherited public members"
    assert b"example/Greeter" not in baseline.read_bytes(), "Class inclusion was ignored"
print("BCV integration passed: baselines, real API changes, package/class/marker filters, inherited members.")
