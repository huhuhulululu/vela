#!/usr/bin/env python3
"""parity.py 的自测，含计划要求的负样本。只使用标准库，macOS / Linux 均可。"""

import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PARITY = Path(__file__).resolve().parent / "parity.py"
SWIFT = "Tests/VelaCoreTests/BOMShapeTests.swift"
TEST_A = "testFullBOMThenJSONIsJSON"
TEST_B = "testPlainCases"


def run_parity(args):
    completed = subprocess.run(
        [sys.executable, "-I", str(PARITY), *args],
        text=True,
        capture_output=True,
        check=False,
    )
    return completed.returncode, completed.stdout, completed.stderr


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(textwrap.dedent(text).lstrip("\n"), encoding="utf-8")


def junit(cases, tests=None, failures=None, errors=0, skipped=0):
    body = []
    fail_n = 0
    skip_n = 0
    for case in cases:
        classname, name = case["classname"], case["name"]
        inner = ""
        if case.get("failure"):
            fail_n += 1
            inner = "<failure message=\"boom\">assertion</failure>"
        elif case.get("skipped"):
            skip_n += 1
            inner = "<skipped/>"
        body.append(
            f'<testcase classname="{classname}" name="{name}">{inner}</testcase>'
        )
    if tests is None:
        tests = len(cases)
    if failures is None:
        failures = fail_n
    if skipped == 0:
        skipped = skip_n
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<testsuite name="suite" tests="{tests}" failures="{failures}" '
        f'errors="{errors}" skipped="{skipped}">\n'
        + "\n".join(body)
        + "\n</testsuite>\n"
    )


class ParityCliTest(unittest.TestCase):
    def test_help_mentions_group_all_and_deferred(self):
        code, out, err = run_parity(["--help"])
        self.assertEqual(code, 0, err)
        text = out + err
        self.assertIn("--group", text)
        self.assertIn("--all", text)
        self.assertIn("deferred:C9", text)

    def test_unknown_group_is_usage_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, _out, err = run_parity(
                ["--group", "no-such-group", "--repo", tmp, "--scope", str(Path(tmp) / "missing.tsv")]
            )
        self.assertEqual(code, 2, err)
        self.assertIn("未知组", err)

    def test_name_match_counts_as_executed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            scope = root / "scope.tsv"
            reports = root / "reports"
            write(
                scope,
                f"group\tswift_file\tswift_test\ndemo\t{SWIFT}\t{TEST_A}\n",
            )
            write(
                reports / "TEST-demo.xml",
                junit(
                    [
                        {
                            "classname": "vela.core.base.BOMShapeTests",
                            "name": TEST_A,
                        }
                    ]
                ),
            )
            code, out, err = run_parity(
                ["--group", "demo", "--repo", str(root), "--scope", str(scope), "--reports", str(reports)]
            )
        self.assertEqual(code, 0, err)
        self.assertIn("executed=1", out)
        self.assertNotIn("executed=0", out)

    def test_missing_mapping_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            scope = root / "scope.tsv"
            reports = root / "reports"
            write(
                scope,
                "group\tswift_file\tswift_test\n"
                f"demo\t{SWIFT}\t{TEST_A}\n"
                f"demo\t{SWIFT}\t{TEST_B}\n",
            )
            write(
                reports / "TEST-demo.xml",
                junit([{"classname": "vela.core.base.BOMShapeTests", "name": TEST_A}]),
            )
            code, _out, err = run_parity(
                ["--group", "demo", "--repo", str(root), "--scope", str(scope), "--reports", str(reports)]
            )
        self.assertNotEqual(code, 0)
        self.assertIn("缺映射", err)
        self.assertIn(TEST_B, err)
        self.assertNotIn(f"缺映射: {SWIFT}#{TEST_A}", err)

    def test_zero_executions_fail_when_suite_is_empty(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            scope = root / "scope.tsv"
            reports = root / "reports"
            parity = root / "android" / "parity"
            write(scope, f"group\tswift_file\tswift_test\ndemo\t{SWIFT}\t{TEST_A}\n")
            write(
                parity / "split-demo.tsv",
                "swift_file\tswift_test\tkotlin_id\n"
                f"{SWIFT}\t{TEST_A}\tvela.core.base.BOMShapeTests#{TEST_A}\n",
            )
            write(
                reports / "TEST-empty.xml",
                '<testsuite name="empty" tests="0" failures="0" errors="0"></testsuite>\n',
            )
            code, _out, err = run_parity(
                [
                    "--group",
                    "demo",
                    "--repo",
                    str(root),
                    "--scope",
                    str(scope),
                    "--parity-dir",
                    str(parity),
                    "--reports",
                    str(reports),
                ]
            )
        self.assertNotEqual(code, 0)
        self.assertIn("执行数为 0", err)

    def test_skipped_testcase_counts_as_zero_executions(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            scope = root / "scope.tsv"
            reports = root / "reports"
            parity = root / "android" / "parity"
            write(scope, f"group\tswift_file\tswift_test\ndemo\t{SWIFT}\t{TEST_A}\n")
            write(
                parity / "split-demo.tsv",
                "swift_file\tswift_test\tkotlin_id\n"
                f"{SWIFT}\t{TEST_A}\tvela.core.base.BOMShapeTests#{TEST_A}\n",
            )
            write(
                reports / "TEST-skipped.xml",
                junit(
                    [
                        {
                            "classname": "vela.core.base.BOMShapeTests",
                            "name": TEST_A,
                            "skipped": True,
                        }
                    ]
                ),
            )
            code, _out, err = run_parity(
                [
                    "--group",
                    "demo",
                    "--repo",
                    str(root),
                    "--scope",
                    str(scope),
                    "--parity-dir",
                    str(parity),
                    "--reports",
                    str(reports),
                ]
            )
        self.assertNotEqual(code, 0)
        self.assertIn("执行数为 0", err)

    def test_junit_failure_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            scope = root / "scope.tsv"
            reports = root / "reports"
            write(scope, f"group\tswift_file\tswift_test\ndemo\t{SWIFT}\t{TEST_A}\n")
            write(
                reports / "TEST-fail.xml",
                junit(
                    [
                        {
                            "classname": "vela.core.base.BOMShapeTests",
                            "name": TEST_A,
                            "failure": True,
                        }
                    ]
                ),
            )
            code, _out, err = run_parity(
                ["--group", "demo", "--repo", str(root), "--scope", str(scope), "--reports", str(reports)]
            )
        self.assertNotEqual(code, 0)
        self.assertIn("测试失败", err)

    def test_comment_link_is_an_explicit_mapping(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            scope = root / "scope.tsv"
            reports = root / "reports"
            write(scope, f"group\tswift_file\tswift_test\ndemo\t{SWIFT}\t{TEST_A}\n")
            write(
                root / "android" / "core" / "src" / "test" / "kotlin" / "DemoTest.kt",
                f"""
                package vela.core

                // parity: {SWIFT}#{TEST_A}
                fun renamedFromSwift() {{}}
                """,
            )
            write(
                reports / "TEST-demo.xml",
                junit([{"classname": "vela.core.DemoTest", "name": "renamedFromSwift"}]),
            )
            code, out, err = run_parity(
                ["--group", "demo", "--repo", str(root), "--scope", str(scope), "--reports", str(reports)]
            )
        self.assertEqual(code, 0, err)
        self.assertIn("executed=1", out)

    def test_group_treats_deferred_c9_as_registered(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            scope = root / "scope.tsv"
            parity = root / "android" / "parity"
            reports = root / "reports"
            reports.mkdir()
            write(scope, f"group\tswift_file\tswift_test\nl0a\t{SWIFT}\t{TEST_A}\n")
            write(
                parity / "skip-l0a.tsv",
                f"swift_file\tswift_test\treason\n{SWIFT}\t{TEST_A}\tdeferred:C9\n",
            )
            code, out, err = run_parity(
                [
                    "--group",
                    "l0a",
                    "--repo",
                    str(root),
                    "--scope",
                    str(scope),
                    "--parity-dir",
                    str(parity),
                    "--reports",
                    str(reports),
                ]
            )
        self.assertEqual(code, 0, err + out)
        self.assertIn("executed=0", out)

    def test_all_fails_when_deferred_is_not_consumed_by_c9(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            scope = root / "scope.tsv"
            parity = root / "android" / "parity"
            reports = root / "reports"
            reports.mkdir()
            write(scope, f"group\tswift_file\tswift_test\nl0a\t{SWIFT}\t{TEST_A}\n")
            write(
                parity / "skip-l0a.tsv",
                f"swift_file\tswift_test\treason\n{SWIFT}\t{TEST_A}\tdeferred:C9\n",
            )
            code, _out, err = run_parity(
                [
                    "--all",
                    "--repo",
                    str(root),
                    "--scope",
                    str(scope),
                    "--parity-dir",
                    str(parity),
                    "--reports",
                    str(reports),
                ]
            )
        self.assertNotEqual(code, 0)
        self.assertIn("deferred", err)
        self.assertIn(TEST_A, err)

    def test_all_passes_when_c9_consumes_deferred(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            scope = root / "scope.tsv"
            parity = root / "android" / "parity"
            reports = root / "reports"
            reports.mkdir()
            write(scope, f"group\tswift_file\tswift_test\nl0a\t{SWIFT}\t{TEST_A}\n")
            write(
                parity / "skip-l0a.tsv",
                f"swift_file\tswift_test\treason\n{SWIFT}\t{TEST_A}\tdeferred:C9\n",
            )
            write(
                parity / "skip-cross.tsv",
                f"swift_file\tswift_test\treason\n{SWIFT}\t{TEST_A}\tC9 已移植\n",
            )
            code, out, err = run_parity(
                [
                    "--all",
                    "--repo",
                    str(root),
                    "--scope",
                    str(scope),
                    "--parity-dir",
                    str(parity),
                    "--reports",
                    str(reports),
                ]
            )
        self.assertEqual(code, 0, err)
        self.assertIn("problems=0", out)

    def test_replaced_by_missing_in_group_skip_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            scope = root / "scope.tsv"
            parity = root / "android" / "parity"
            reports = root / "reports"
            write(scope, f"group\tswift_file\tswift_test\ndemo\t{SWIFT}\t{TEST_A}\n")
            write(
                parity / "skip-demo.tsv",
                f"swift_file\tswift_test\treason\n{SWIFT}\t{TEST_A}\t不移植；replaced-by:vela.app.MissingTest#testGhost\n",
            )
            write(
                reports / "TEST-demo.xml",
                junit([{"classname": "vela.app.OtherTest", "name": "testOther"}]),
            )
            code, _out, err = run_parity(
                [
                    "--group",
                    "demo",
                    "--repo",
                    str(root),
                    "--scope",
                    str(scope),
                    "--parity-dir",
                    str(parity),
                    "--reports",
                    str(reports),
                ]
            )
        self.assertNotEqual(code, 0)
        self.assertIn("replaced-by", err)
        self.assertIn("vela.app.MissingTest#testGhost", err)

    def test_replaced_by_missing_or_skipped_or_failed_in_global_skip_fails_all(self):
        for kind, case in [
            ("missing", {"classname": "vela.app.OtherTest", "name": "testOther"}),
            ("skipped", {"classname": "vela.app.RealTest", "name": "testTarget", "skipped": True}),
            ("failed", {"classname": "vela.app.RealTest", "name": "testTarget", "failure": True}),
        ]:
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                scope = root / "scope.tsv"
                parity = root / "android" / "parity"
                reports = root / "reports"
                write(scope, f"group\tswift_file\tswift_test\ndemo\t{SWIFT}\t{TEST_A}\n")
                write(
                    parity / "skip.tsv",
                    f"swift_file\tswift_test\treason\nTests/VelaPhoneTests/Fake.swift\ttestGlobal\treplaced-by:vela.app.RealTest#testTarget\n",
                )
                write(
                    reports / "TEST-demo.xml",
                    junit(
                        [
                            {"classname": "vela.core.base.BOMShapeTests", "name": TEST_A},
                            case,
                        ]
                    ),
                )
                code, _out, err = run_parity(
                    [
                        "--all",
                        "--repo",
                        str(root),
                        "--scope",
                        str(scope),
                        "--parity-dir",
                        str(parity),
                        "--reports",
                        str(reports),
                    ]
                )
                self.assertNotEqual(code, 0, f"{kind} should fail")
                if kind == "failed":
                    self.assertIn("测试失败", err)
                else:
                    self.assertIn("replaced-by", err)

    def test_replaced_by_executed_method_and_class_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            scope = root / "scope.tsv"
            parity = root / "android" / "parity"
            reports = root / "reports"
            write(
                scope,
                "group\tswift_file\tswift_test\n"
                f"demo\t{SWIFT}\t{TEST_A}\n"
                f"demo\t{SWIFT}\t{TEST_B}\n",
            )
            write(
                parity / "skip-demo.tsv",
                "swift_file\tswift_test\treason\n"
                f"{SWIFT}\t{TEST_A}\t不移植；replaced-by:vela.app.RealTest#testExact\n"
                f"{SWIFT}\t{TEST_B}\t不移植；replaced-by:vela.app.RealClassTest\n",
            )
            write(
                parity / "skip.tsv",
                "swift_file\tswift_test\treason\n"
                "Tests/VelaPhoneTests/Fake.swift\ttestGlobal\treplaced-by:vela.app.RealTest#testExact\n",
            )
            write(
                reports / "TEST-demo.xml",
                junit(
                    [
                        {"classname": "vela.app.RealTest", "name": "testExact"},
                        {"classname": "vela.app.RealClassTest", "name": "testAnyMethod"},
                    ]
                ),
            )
            code, out, err = run_parity(
                [
                    "--all",
                    "--repo",
                    str(root),
                    "--scope",
                    str(scope),
                    "--parity-dir",
                    str(parity),
                    "--reports",
                    str(reports),
                ]
            )
        self.assertEqual(code, 0, err)
        self.assertIn("problems=0", out)

    def test_non_c9_deferred_prefix_fails_group_and_all(self):
        for flag in ["--group", "--all"]:
            with self.subTest(flag=flag), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                scope = root / "scope.tsv"
                parity = root / "android" / "parity"
                reports = root / "reports"
                reports.mkdir()
                write(scope, f"group\tswift_file\tswift_test\nsync\t{SWIFT}\t{TEST_A}\n")
                write(
                    parity / "skip-sync.tsv",
                    f"swift_file\tswift_test\treason\n{SWIFT}\t{TEST_A}\tdeferred:S6 尚未映射\n",
                )
                args = (
                    ["--group", "sync"]
                    if flag == "--group"
                    else ["--all"]
                ) + [
                    "--repo",
                    str(root),
                    "--scope",
                    str(scope),
                    "--parity-dir",
                    str(parity),
                    "--reports",
                    str(reports),
                ]
                code, _out, err = run_parity(args)
                self.assertNotEqual(code, 0)
                self.assertIn("deferred", err)
                self.assertIn(TEST_A, err)

    def test_sites_group_recognizes_all_site_files_and_rejects_missing_mapping(self):
        site_files = [
            "Tests/VelaCoreTests/Cam4DirectoryTests.swift",
            "Tests/VelaCoreTests/Cam4StreamTests.swift",
            "Tests/VelaCoreTests/ChaturbateAdapterTests.swift",
            "Tests/VelaCoreTests/RoomKeyTests.swift",
            "Tests/VelaCoreTests/SiteCatalogTests.swift",
            "Tests/VelaCoreTests/SiteDirectoryTests.swift",
            "Tests/VelaCoreTests/SiteStorageTests.swift",
            "Tests/VelaCoreTests/SyncSitesTests.swift",
            "Tests/VelaPhoneTests/SiteWiringTests.swift",
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            reports = root / "reports"
            reports.mkdir()
            for rel in site_files:
                write(
                    root / rel,
                    "final class Sample {\n    func testPlaceholder() {}\n}\n",
                )
            code, _out, err = run_parity(
                [
                    "--group",
                    "sites",
                    "--repo",
                    str(root),
                    "--parity-dir",
                    str(root / "android" / "parity"),
                    "--reports",
                    str(reports),
                ]
            )
        self.assertEqual(code, 1, err)
        self.assertNotIn("未知组", err)
        for rel in site_files:
            self.assertIn(f"缺映射: {rel}#testPlaceholder（组 sites）", err)

    def test_real_repository_sites_group_passes(self):
        code, out, err = run_parity(["--group", "sites", "--repo", str(ROOT)])
        self.assertEqual(code, 0, err)
        self.assertIn("problems=0", out)
        self.assertIn("executed=72", out)

    def test_real_repository_all_passes(self):
        code, out, err = run_parity(["--all", "--repo", str(ROOT)])
        self.assertEqual(code, 0, err)
        self.assertIn("problems=0", out)


if __name__ == "__main__":
    unittest.main(verbosity=2)

