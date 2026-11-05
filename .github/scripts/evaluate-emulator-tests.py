#!/usr/bin/env python3
"""Decides whether the emulator job passes.

The emulator suite is a soft gate: individual failing tests are reported but do not fail the build.
The build fails only when MORE than --max-failure-percent of the suite did not pass. "Did not pass"
counts three things, so that a run cannot look healthy by not running:

  * tests the XML records as failed or errored,
  * tests declared in the sources (`@Test`) that never reported a result -- the instrumentation
    process crashed, or the run died before reaching them, and
  * nothing at all: no result files, or no tests, is a failure, never a pass. (A green run with
    zero tests is exactly what an R8-broken androidTest APK produces; see build.yml.)

Skipped tests (@Ignore, failed assumptions) are neither passes nor failures and stay out of the rate.

Exit status: 0 = gate passes, 1 = gate fails. Reads AGP's JUnit XML (`TEST-*.xml`); writes a report to
stdout, GitHub annotations, and the job summary when GITHUB_STEP_SUMMARY is set.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

# Matches a test declaration at the start of a line, so `// @Test` and `* @Test` in comments do not count.
TEST_ANNOTATION = re.compile(r"^\s*@Test\b", re.MULTILINE)

# Logged by RetryRule (androidTest) when a test failed and then passed on a later attempt.
FLAKY_LOG = re.compile(r"PapyrusRetry\b[^:]*:\s*(FLAKY .*)")

MAX_ANNOTATIONS = 10  # GitHub shows at most ten per step anyway.
MAX_LISTED = 25


@dataclass(frozen=True)
class Failure:
    test: str
    detail: str


@dataclass(frozen=True)
class Tally:
    passed: int
    failed: int
    skipped: int
    declared: int

    @property
    def missing(self) -> int:
        """Declared tests that reported nothing, not even a skip."""
        return max(0, self.declared - (self.passed + self.failed + self.skipped))

    @property
    def counted(self) -> int:
        return self.passed + self.failed + self.missing

    @property
    def bad(self) -> int:
        return self.failed + self.missing

    def failure_percent(self) -> float:
        return 100.0 * self.bad / self.counted if self.counted else 100.0


@dataclass(frozen=True)
class Verdict:
    ok: bool
    headline: str


def decide(tally: Tally, max_failure_percent: float, gradle_exit: int | None) -> Verdict:
    if tally.passed + tally.failed + tally.skipped == 0:
        return Verdict(
            False,
            "No emulator test reported a result: the emulator did not start, the build failed, or the "
            "instrumentation process died before the first test. See the 'Run emulator tests' step.",
        )
    if tally.counted == 0:
        return Verdict(False, "Every emulator test was skipped, so nothing was actually checked.")
    if gradle_exit not in (None, 0) and tally.bad == 0:
        # Every recorded test passed, yet Gradle failed: the build or device broke in a way the results
        # cannot explain. Tolerating that would hide an infrastructure problem.
        return Verdict(False, f"Gradle exited with {gradle_exit} although no test is recorded as failing.")
    # Integer-friendly comparison: exactly the limit still passes ("more than 80% fail" is the failure).
    if tally.bad * 100 > max_failure_percent * tally.counted:
        return Verdict(
            False,
            f"{tally.bad} of {tally.counted} emulator tests did not pass "
            f"({tally.failure_percent():.1f}%), more than the {max_failure_percent:g}% allowed.",
        )
    if tally.bad:
        return Verdict(
            True,
            f"{tally.bad} of {tally.counted} emulator tests did not pass "
            f"({tally.failure_percent():.1f}%), within the {max_failure_percent:g}% allowed. Not a build failure.",
        )
    return Verdict(True, f"All {tally.passed} emulator tests passed.")


def count_declared_tests(source_root: Path) -> int:
    total = 0
    if source_root.is_dir():
        for path in source_root.rglob("*"):
            if path.suffix in {".kt", ".java"} and path.is_file():
                total += len(TEST_ANNOTATION.findall(path.read_text(encoding="utf-8", errors="replace")))
    return total


def first_line(text: str | None, limit: int = 300) -> str:
    for line in (text or "").splitlines():
        if line.strip():
            return line.strip()[:limit]
    return ""


def read_results(results_dir: Path) -> tuple[int, int, int, list[Failure], int]:
    """Returns (passed, failed, skipped, failures, files_read) from every TEST-*.xml under [results_dir].

    A test that appears more than once (two devices, a re-run) counts once, as passed if any copy passed.
    """
    outcome: dict[str, str] = {}
    detail: dict[str, str] = {}
    files_read = 0
    for xml_file in sorted(results_dir.rglob("TEST-*.xml")) if results_dir.is_dir() else []:
        try:
            root = ET.parse(xml_file).getroot()
        except ET.ParseError as error:
            print(f"::warning::Could not parse {xml_file}: {error}")
            continue
        files_read += 1
        for case in root.iter("testcase"):
            name = f"{case.get('classname', '?')}#{case.get('name', '?')}"
            bad_node = next((c for c in case if c.tag in ("failure", "error")), None)
            if bad_node is not None:
                status = "failed"
                message = first_line(bad_node.get("message")) or first_line(bad_node.text)
            elif any(c.tag == "skipped" for c in case):
                status, message = "skipped", ""
            else:
                status, message = "passed", ""
            previous = outcome.get(name)
            if previous == "passed" or (previous == "skipped" and status == "failed"):
                continue  # keep the better outcome
            outcome[name] = status
            detail[name] = message
    failures = [Failure(name, detail[name]) for name, status in sorted(outcome.items()) if status == "failed"]
    values = list(outcome.values())
    return values.count("passed"), values.count("failed"), values.count("skipped"), failures, files_read


def read_flaky(logcat: Path | None) -> list[str]:
    if logcat is None or not logcat.is_file():
        return []
    seen: dict[str, None] = {}
    for line in logcat.read_text(encoding="utf-8", errors="replace").splitlines():
        match = FLAKY_LOG.search(line)
        if match:
            seen[match.group(1)] = None
    return list(seen)


def read_exit_code(path: Path | None) -> int | None:
    try:
        return int(path.read_text().strip()) if path else None
    except (OSError, ValueError):
        return None


def escape_annotation(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def short(test: str) -> str:
    """`com.papyrus.app.e2e.LibraryTest#sortOrdersTheList` -> `LibraryTest#sortOrdersTheList`."""
    return test.rsplit(".", 1)[-1] if "#" in test else test


def build_report(tally: Tally, verdict: Verdict, failures: list[Failure], flaky: list[str], limit: float) -> str:
    icon = "✅" if verdict.ok else "❌"
    lines = [
        "## Emulator tests",
        "",
        f"{icon} {verdict.headline}",
        "",
        "| Passed | Failed | Not run | Skipped | Declared | Not passing | Limit |",
        "| ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
        f"| {tally.passed} | {tally.failed} | {tally.missing} | {tally.skipped} | {tally.declared} "
        f"| {tally.failure_percent():.1f}% | {limit:g}% |",
        "",
    ]
    if failures:
        lines += ["### Failing tests", ""]
        lines += [f"- `{short(f.test)}` — {f.detail or 'no message'}" for f in failures[:MAX_LISTED]]
        if len(failures) > MAX_LISTED:
            lines.append(f"- … and {len(failures) - MAX_LISTED} more (see the `emulator-test-results` artifact)")
        lines.append("")
    if tally.missing:
        lines += [
            f"{tally.missing} declared test(s) reported no result, which usually means the instrumentation "
            "process crashed. Check `logcat.txt` in the `emulator-test-results` artifact.",
            "",
        ]
    if flaky:
        lines += ["### Passed only after a retry (flaky)", ""]
        lines += [f"- {entry.removeprefix('FLAKY ')}" for entry in flaky[:MAX_LISTED]]
        lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--results-dir", type=Path, default=Path("app/build/outputs/androidTest-results"))
    parser.add_argument("--tests-dir", type=Path, default=Path("app/src/androidTest"))
    parser.add_argument("--ci-dir", type=Path, default=Path("app/build/emulator-ci"),
                        help="where run-emulator-tests.sh left gradle-exit-code and logcat.txt")
    parser.add_argument("--max-failure-percent", type=float, default=80.0)
    args = parser.parse_args()

    passed, failed, skipped, failures, files_read = read_results(args.results_dir)
    declared = count_declared_tests(args.tests_dir)
    tally = Tally(passed, failed, skipped, declared)
    gradle_exit = read_exit_code(args.ci_dir / "gradle-exit-code")
    verdict = decide(tally, args.max_failure_percent, gradle_exit)
    flaky = read_flaky(args.ci_dir / "logcat.txt")

    report = build_report(tally, verdict, failures, flaky, args.max_failure_percent)
    print(report)
    print(f"(result files read: {files_read}; gradle exit code: {gradle_exit if gradle_exit is not None else 'unknown'})")

    if verdict.ok:
        for failure in failures[:MAX_ANNOTATIONS]:
            print(f"::warning title=Emulator test failed (tolerated)::{escape_annotation(f'{short(failure.test)}: {failure.detail}')}")
        for entry in flaky[:MAX_ANNOTATIONS]:
            print(f"::notice title=Flaky emulator test::{escape_annotation(entry.removeprefix('FLAKY '))}")
    else:
        print(f"::error title=Emulator tests::{escape_annotation(verdict.headline)}")

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(report + "\n")

    return 0 if verdict.ok else 1


if __name__ == "__main__":
    sys.exit(main())
