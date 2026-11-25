#!/usr/bin/env python3
"""对照 Swift 测试与 Kotlin JUnit 报告。

按组核对：每条 Swift 测试要么在 skip 表里，要么能在 JUnit XML 里找到一次真正执行。
`deferred:C9` 在 --group 下算已登记；--all 时必须被组 cross 消化，否则失败。
执行数为 0（空套件、skipped、或显式映射对不上）算失败。缺映射也算失败。

只使用标准库。从仓库任意工作目录运行都可以，路径按本文件位置解析。
"""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

GROUPS = (
    "l0a",
    "l0b",
    "hls",
    "lifecycle",
    "stream",
    "feed",
    "taste",
    "ledger",
    "recommend",
    "curation",
    "selection",
    "sync",
    "audio",
    "cross",
)

# 不归属任何移植组，必须逐条写在 android/parity/skip.tsv。
GLOBAL_SKIP_FILES = {
    "Tests/VelaCoreTests/AssignIfTests.swift",
    "Tests/VelaCoreTests/MenuBarVisibilityTests.swift",
    "Tests/VelaCoreTests/WindowDragPolicyTests.swift",
    "Tests/VelaCoreTests/PointerPollingTests.swift",
    "Tests/VelaCoreTests/PortraitFitTests.swift",
    "Tests/VelaCoreTests/QualityGovernorTests.swift",
    "Tests/VelaRelayTests/RelayMultiSourceTests.swift",
    "Tests/VelaCoreTests/AudioTransitionRunnerTests.swift",
    "Tests/VelaCoreTests/DiagnosticDigestTests.swift",
    "Tests/VelaCoreTests/DwellFocusTests.swift",
    "Tests/VelaPhoneTests/AudioFocusControllerTests.swift",
    "Tests/VelaPhoneTests/DualAudioFocusTests.swift",
    "Tests/VelaPhoneTests/DualDwellTests.swift",
    "Tests/VelaPhoneTests/DualPictureInPictureTests.swift",
    "Tests/VelaPhoneTests/DualQualityTests.swift",
    "Tests/VelaPhoneTests/DualWindowTests.swift",
    "Tests/VelaPhoneTests/FailureSampleTests.swift",
    "Tests/VelaPhoneTests/LifecycleWiringTests.swift",
    "Tests/VelaPhoneTests/PhoneHousekeepingTests.swift",
    "Tests/VelaPhoneTests/PhoneViewLogicTests.swift",
    "Tests/VelaPhoneTests/PictureInPictureWiringTests.swift",
    "Tests/VelaPhoneTests/SharedCopyLayoutTests.swift",
    "Tests/VelaPhoneTests/TripleLaneMediaTests.swift",
    "Tests/VelaPhoneTests/TriplePictureInPictureTests.swift",
    "Tests/VelaPhoneTests/TripleWindowTests.swift",
}

# 整文件归属。混文件见 PARTIAL。
WHOLE = {
    "Tests/VelaCoreTests/BOMShapeTests.swift": "l0a",
    "Tests/VelaCoreTests/EndpointBackoffTests.swift": "l0a",
    "Tests/VelaCoreTests/StreamRenditionTests.swift": "l0b",
    "Tests/VelaCoreTests/PreferenceQuarantineTests.swift": "l0b",
    "Tests/VelaCoreTests/DeferredSaverTests.swift": "l0b",
    "Tests/VelaCoreTests/UpstreamRenewalTests.swift": "hls",
    "Tests/VelaCoreTests/RenewalCoordinatorTests.swift": "hls",
    "Tests/VelaCoreTests/PlaybackPolicyTests.swift": "hls",
    "Tests/VelaCoreTests/MuteAudioTests.swift": "hls",
    "Tests/VelaCoreTests/AudioNetworkTests.swift": "hls",
    "Tests/VelaCoreTests/PlaybackRefreshTests.swift": "hls",
    "Tests/VelaCoreTests/PlaybackErrorTests.swift": "hls",
    "Tests/VelaCoreTests/DisplayFitTests.swift": "hls",
    "Tests/VelaCoreTests/LaneQualityTests.swift": "hls",
    "Tests/VelaCoreTests/PlaybackFailureClassifierTests.swift": "hls",
    "Tests/VelaCoreTests/SkipTrackerTests.swift": "hls",
    "Tests/VelaCoreTests/RelayFeedTests.swift": "hls",
    "Tests/VelaCoreTests/RelayWaitTests.swift": "hls",
    "Tests/VelaCoreTests/RelaySourceTableTests.swift": "hls",
    "Tests/VelaCoreTests/LifecycleEpochTests.swift": "lifecycle",
    "Tests/VelaCoreTests/PhoneLifecycleTests.swift": "lifecycle",
    "Tests/VelaCoreTests/PiPPolicyTests.swift": "lifecycle",
    "Tests/VelaCoreTests/ChallengeGateTests.swift": "lifecycle",
    "Tests/VelaCoreTests/AwayRetryTests.swift": "lifecycle",
    "Tests/VelaCoreTests/StreamSourcingTests.swift": "stream",
    "Tests/VelaCoreTests/ChatContextProbeTests.swift": "stream",
    "Tests/VelaCoreTests/GeoRestrictedTests.swift": "stream",
    "Tests/VelaCoreTests/CatalogTests.swift": "feed",
    "Tests/VelaCoreTests/SiteSearchTests.swift": "feed",
    "Tests/VelaCoreTests/FeedAnchorTests.swift": "feed",
    "Tests/VelaCoreTests/FeedCacheTests.swift": "feed",
    "Tests/VelaCoreTests/FeedDelistTests.swift": "feed",
    "Tests/VelaCoreTests/StartupCatalogTests.swift": "feed",
    "Tests/VelaCoreTests/TargetedMergeTests.swift": "feed",
    "Tests/VelaCoreTests/LoadSlotTests.swift": "feed",
    "Tests/VelaCoreTests/InfoVisibilityTests.swift": "feed",
    "Tests/VelaCoreTests/StageFlowTests.swift": "feed",
    "Tests/VelaCoreTests/ShortcutSearchTests.swift": "feed",
    "Tests/VelaCoreTests/TasteTests.swift": "taste",
    "Tests/VelaCoreTests/TasteRecordingOverloadTests.swift": "taste",
    "Tests/VelaCoreTests/HotTagsTests.swift": "taste",
    "Tests/VelaCoreTests/WatchLedgerTests.swift": "ledger",
    "Tests/VelaCoreTests/WatchAccountingTests.swift": "ledger",
    "Tests/VelaCoreTests/PromptRulesTests.swift": "ledger",
    "Tests/VelaCoreTests/FeedRankingTests.swift": "recommend",
    "Tests/VelaCoreTests/RecommendTuningTests.swift": "recommend",
    "Tests/VelaCoreTests/RecommendScenarioTests.swift": "recommend",
    "Tests/VelaCoreTests/FreshCamsTests.swift": "recommend",
    "Tests/VelaCoreTests/FeedReorderTests.swift": "recommend",
    "Tests/VelaCoreTests/FavoriteStatusTests.swift": "curation",
    "Tests/VelaCoreTests/FavoritesBoardTests.swift": "curation",
    "Tests/VelaCoreTests/LastBroadcastTests.swift": "curation",
    "Tests/VelaCoreTests/LoadCommitTests.swift": "curation",
    "Tests/VelaCoreTests/LaneSelectionTests.swift": "selection",
    "Tests/VelaCoreTests/LaneSettlementTests.swift": "selection",
    "Tests/VelaCoreTests/AudioTransitionTests.swift": "audio",
    "Tests/VelaCoreTests/ReviewFixTests.swift": "cross",
    "Tests/VelaPhoneTests/AuditFixTests.swift": "cross",
    "Tests/VelaCoreTests/DwellTests.swift": "cross",
    "Tests/VelaCoreTests/CurationTests.swift": "cross",
    "Tests/VelaCoreTests/VetoVisibilityTests.swift": "cross",
    "Tests/VelaCoreTests/FileLimitTests.swift": "cross",
    "Tests/VelaCoreTests/SearchFix13Tests.swift": "cross",
    "Tests/VelaCoreTests/SyncVectorsTests.swift": "sync",
    "Tests/VelaCoreTests/SyncSimulationTests.swift": "sync",
    "Tests/VelaCoreTests/SyncCoordinatorTests.swift": "sync",
    "Tests/VelaCoreTests/SyncLifecycleTests.swift": "sync",
    "Tests/VelaCoreTests/SyncCodeTests.swift": "sync",
    "Tests/VelaCoreTests/SyncProjectionTests.swift": "sync",
    "Tests/VelaCoreTests/SyncDocumentTests.swift": "sync",
    "Tests/VelaCoreTests/SyncMergeTests.swift": "sync",
    "Tests/VelaCoreTests/SyncMigrationTests.swift": "sync",
    "Tests/VelaCoreTests/SyncOpsTests.swift": "sync",
    "Tests/VelaCoreTests/SyncPrimitivesTests.swift": "sync",
    "Tests/VelaCoreTests/SyncRestampTests.swift": "sync",
    "Tests/VelaCoreTests/SyncTasteTests.swift": "sync",
    "Tests/VelaSyncTests/SyncCryptoTests.swift": "sync",
    "Tests/VelaSyncTests/SyncCryptoVectorsTests.swift": "sync",
    "Tests/VelaSyncTests/SyncFileJournalTests.swift": "sync",
    "Tests/VelaSyncTests/SyncIntegrationTests.swift": "sync",
    "Tests/VelaSyncTests/SyncJoinCommitLockTests.swift": "sync",
    "Tests/VelaSyncTests/SyncJoinRealFilesTests.swift": "sync",
    "Tests/VelaSyncTests/SyncJoinTransactionTests.swift": "sync",
    "Tests/VelaSyncTests/SyncKeychainTests.swift": "sync",
    "Tests/VelaSyncTests/SyncLiveServerTests.swift": "sync",
    "Tests/VelaSyncTests/SyncLocalOnlyQuotaTests.swift": "sync",
    "Tests/VelaSyncTests/SyncSchedulerTests.swift": "sync",
    "Tests/VelaSyncTests/SyncSingleFlightTests.swift": "sync",
    "Tests/VelaSyncTests/SyncSourceGuardTests.swift": "sync",
    "Tests/VelaSyncTests/SyncStateBackupTests.swift": "sync",
    "Tests/VelaSyncTests/SyncStatusTextTests.swift": "sync",
    "Tests/VelaSyncTests/SyncTransportLimitsTests.swift": "sync",
}

PARTIAL = {
    "Tests/VelaCoreTests/VelaCoreTests.swift": {
        "testFormattingAndTokens": "l0a",
        "testPlaylistLadderPicksRestingRung": "hls",
        "testStreamTickets": "stream",
        "testStreamRequestDoesNotAskForHighestBandwidth": "stream",
        "testCatalogFetchRejectsChallenge": "feed",
        "testFeedKeepsPlaceFiltersAndPrefetches": "feed",
        "testWheelCommitsOneRoom": "feed",
        "testPresenceIsConditional": "feed",
    },
    "Tests/VelaCoreTests/PlaybackFlowFixTests.swift": {
        "testParseReadsDiscontinuityAndLastURI": "l0b",
        "testStuckWithoutProgressStillRecoversOrGivesUp": "hls",
        "testStuckRearmsWhileBufferGrowsOrRenewing": "hls",
        "testStuckIgnoreTakesPriorityOverProgress": "hls",
        "testBufferAdvanced": "hls",
        "testEndOfListKeepsReasonAndDoesNotCount": "hls",
        "testEndOfListAfterSomeSkipsIsStillEndOfList": "hls",
        "testMovedHasNoNotice": "hls",
        "testLimitOnlyAfterRealConsecutiveSkips": "hls",
        "testRewindWithDiscontinuityAdoptsNewBody": "hls",
        "testRewindWithoutDiscontinuityStaysOlder": "hls",
        "testAddressChangedAdoptsEvenWhenSequenceDoesNotAdvance": "hls",
        "testSameSequenceNewURIAdoptsAndClearsReplay": "hls",
        "testSameSequenceSameURIStaysUnchanged": "hls",
        "testSameSequenceWithoutURIsStaysUnchanged": "hls",
    },
}

FUNC_RE = re.compile(
    r"^\s*(?:(?:public|internal|private|open|final|override)\s+)*"
    r"func\s+(test[A-Za-z0-9_]+)\s*\(",
    re.M,
)
PARITY_RE = re.compile(r"parity:\s*(\S+?\.swift)#(test[A-Za-z0-9_]+)")
REPLACED_BY_RE = re.compile(r"replaced-by:([^\s；;,，]*)")
FUN_RE = re.compile(r"^\s*fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(")
BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT_RE = re.compile(r"//.*?$", re.M)


@dataclass(frozen=True)
class SwiftTest:
    file: str
    name: str

    @property
    def key(self) -> str:
        return f"{self.file}#{self.name}"


@dataclass(frozen=True)
class SkipRow:
    test: SwiftTest
    reason: str
    group: str  # "" 表示全局 skip.tsv


@dataclass(frozen=True)
class Case:
    classname: str
    name: str
    failed: bool
    skipped: bool


def repo_root_from_file() -> Path:
    return Path(__file__).resolve().parents[2]


def strip_swift_comments(text: str) -> str:
    text = BLOCK_COMMENT_RE.sub("", text)
    return LINE_COMMENT_RE.sub("", text)


def parse_swift_tests(root: Path) -> list[SwiftTest]:
    tests_dir = root / "Tests"
    found: list[SwiftTest] = []
    if not tests_dir.is_dir():
        return found
    for path in sorted(tests_dir.rglob("*.swift")):
        rel = path.relative_to(root).as_posix()
        text = strip_swift_comments(path.read_text(encoding="utf-8", errors="replace"))
        for name in FUNC_RE.findall(text):
            found.append(SwiftTest(rel, name))
    return found


def group_of(test: SwiftTest) -> str | None:
    if test.file in GLOBAL_SKIP_FILES:
        return None
    partial = PARTIAL.get(test.file)
    if partial is not None:
        return partial.get(test.name)
    return WHOLE.get(test.file)


def read_tsv(path: Path) -> list[list[str]]:
    if not path.is_file():
        return []
    rows: list[list[str]] = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        cols = raw.split("\t")
        if cols and cols[0].strip() in {"swift_file", "group"}:
            continue
        rows.append([c.strip() for c in cols])
    return rows


def load_scope(path: Path | None) -> list[tuple[str, SwiftTest]]:
    if path is None or not path.is_file():
        return []
    scoped: list[tuple[str, SwiftTest]] = []
    for cols in read_tsv(path):
        if len(cols) < 3 or not cols[2]:
            continue
        scoped.append((cols[0], SwiftTest(cols[1], cols[2])))
    return scoped


def load_skips(parity_dir: Path) -> list[SkipRow]:
    rows: list[SkipRow] = []
    if not parity_dir.is_dir():
        return rows
    files = [("skip.tsv", "")]
    for path in sorted(parity_dir.glob("skip-*.tsv")):
        group = path.name[len("skip-") : -len(".tsv")]
        files.append((path.name, group))
    for name, group in files:
        for cols in read_tsv(parity_dir / name):
            if len(cols) < 3:
                continue
            rows.append(SkipRow(SwiftTest(cols[0], cols[1]), cols[2], group))
    return rows


def load_splits(parity_dir: Path) -> dict[tuple[str, str, str], list[str]]:
    """(group, file, test) -> kotlin ids。group 为空表示所有组都可见（目前没有这种文件）。"""
    found: dict[tuple[str, str, str], list[str]] = {}
    if not parity_dir.is_dir():
        return found
    for path in sorted(parity_dir.glob("split-*.tsv")):
        group = path.name[len("split-") : -len(".tsv")]
        for cols in read_tsv(path):
            if len(cols) < 3 or not cols[2]:
                continue
            found.setdefault((group, cols[0], cols[1]), []).append(cols[2])
    return found


def load_comment_links(root: Path) -> dict[SwiftTest, list[str]]:
    links: dict[SwiftTest, list[str]] = {}
    android = root / "android"
    if not android.is_dir():
        return links
    for path in android.rglob("*.kt"):
        if "build" in path.parts or ".gradle" in path.parts:
            continue
        pending: list[SwiftTest] = []
        text = path.read_text(encoding="utf-8", errors="replace")
        for line in text.splitlines():
            match = PARITY_RE.search(line)
            if match:
                pending.append(SwiftTest(match.group(1), match.group(2)))
            fun = FUN_RE.match(line)
            if fun:
                if pending:
                    for test in pending:
                        links.setdefault(test, []).append(fun.group(1))
                pending = []
    return links


def active_groups(parity_dir: Path) -> set[str]:
    found: set[str] = set()
    if not parity_dir.is_dir():
        return found
    for path in parity_dir.iterdir():
        name = path.name
        if name.startswith("skip-") and name.endswith(".tsv"):
            found.add(name[len("skip-") : -len(".tsv")])
        elif name.startswith("split-") and name.endswith(".tsv"):
            found.add(name[len("split-") : -len(".tsv")])
    return found


def load_cases(report_dirs: list[Path]) -> list[Case]:
    cases: list[Case] = []
    for directory in report_dirs:
        if not directory.is_dir():
            continue
        for xml_path in sorted(directory.rglob("*.xml")):
            try:
                root = ET.parse(xml_path).getroot()
            except ET.ParseError as exc:
                raise SystemExit(f"JUnit XML 无法解析: {xml_path}: {exc}") from exc
            if root.tag == "testsuite":
                suites = [root]
            elif root.tag == "testsuites":
                suites = list(root.findall("testsuite"))
            else:
                continue
            for suite in suites:
                for node in suite.findall("testcase"):
                    name = node.get("name") or ""
                    if name.endswith("()"):
                        name = name[:-2]
                    cases.append(
                        Case(
                            classname=node.get("classname") or "",
                            name=name,
                            failed=node.find("failure") is not None
                            or node.find("error") is not None,
                            skipped=node.find("skipped") is not None,
                        )
                    )
    return cases


def default_report_dirs(root: Path) -> list[Path]:
    base = root / "android"
    found: list[Path] = []
    if not base.is_dir():
        return found
    for path in base.rglob("test-results"):
        if "build" in path.parts and path.is_dir():
            found.append(path)
    return found


def is_deferred(reason: str) -> bool:
    return reason.strip().startswith("deferred:C9")


def is_any_deferred(reason: str) -> bool:
    return reason.strip().startswith("deferred:")


def replaced_by_targets(reason: str) -> list[str]:
    return REPLACED_BY_RE.findall(reason)


def replaced_by_matches(case: Case, target: str) -> bool:
    if "#" in target:
        classname, method = target.split("#", 1)
        if not method or case.name != method:
            return False
        if not classname:
            return True
        if "." in classname:
            return case.classname == classname
        return case.classname == classname or case.classname.endswith("." + classname)
    if "." in target:
        return case.classname == target
    return case.classname == target or case.classname.endswith("." + target) or case.name == target


def check_replaced_by(row: SkipRow, cases: list[Case]) -> list[str]:
    targets = replaced_by_targets(row.reason)
    if not targets:
        return []
    problems: list[str] = []
    for target in targets:
        if not target.strip():
            problems.append(f"replaced-by 缺少目标: {row.test.key}")
            continue
        matched = [case for case in cases if replaced_by_matches(case, target)]
        ran = [case for case in matched if not case.skipped]
        if not ran:
            problems.append(f"replaced-by 执行数为 0: {row.test.key} -> {target}")
            continue
        failed = [case for case in ran if case.failed]
        if failed:
            problems.append(
                f"测试失败: {row.test.key} ({failed[0].classname}#{failed[0].name})"
            )
    return problems


def kotlin_matches(case: Case, kotlin_id: str) -> bool:
    if "#" in kotlin_id:
        classname, method = kotlin_id.split("#", 1)
    else:
        classname, method = "", kotlin_id
    if case.name != method:
        return False
    if not classname:
        return True
    simple = classname.rsplit(".", 1)[-1]
    return (
        case.classname == classname
        or case.classname == simple
        or case.classname.endswith("." + simple)
    )


def select_cases(cases: list[Case], kotlin_ids: list[str] | None, test: SwiftTest) -> list[Case]:
    if kotlin_ids:
        return [case for case in cases if any(kotlin_matches(case, kid) for kid in kotlin_ids)]
    named = [case for case in cases if case.name == test.name]
    stem = Path(test.file).stem
    preferred = [
        case
        for case in named
        if case.classname == stem or case.classname.endswith("." + stem)
    ]
    return preferred or named


def check_group(
    group: str,
    tests: list[SwiftTest],
    skips: list[SkipRow],
    splits: dict[tuple[str, str, str], list[str]],
    comments: dict[SwiftTest, list[str]],
    cases: list[Case],
) -> tuple[list[str], int]:
    problems: list[str] = []
    executed = 0
    for test in tests:
        rows = [
            row
            for row in skips
            if row.test == test and row.group in {"", group}
        ]
        if any(is_deferred(row.reason) for row in rows):
            continue
        non_c9_deferred = [
            row for row in rows if is_any_deferred(row.reason) and not is_deferred(row.reason)
        ]
        if non_c9_deferred:
            for row in non_c9_deferred:
                problems.append(f"未消化的 deferred: {test.key} ({row.reason.strip()})")
            continue
        non_empty_rows = [row for row in rows if row.reason.strip()]
        if non_empty_rows:
            for row in non_empty_rows:
                problems.extend(check_replaced_by(row, cases))
            continue
        explicit = list(splits.get((group, test.file, test.name), []))
        explicit.extend(comments.get(test, []))
        # 注释给出的是方法名，和 split 的 kotlin id 同一套匹配。
        matched = select_cases(cases, explicit or None, test)
        ran = [case for case in matched if not case.skipped]
        if not ran:
            if explicit:
                shown = ", ".join(explicit)
                problems.append(f"执行数为 0: {test.key} -> {shown}")
            else:
                problems.append(f"缺映射: {test.key}（组 {group}）")
            continue
        failed = [case for case in ran if case.failed]
        if failed:
            problems.append(
                f"测试失败: {test.key} ({failed[0].classname}#{failed[0].name})"
            )
            continue
        executed += len(ran)
    return problems, executed


def structural_problems(tests: list[SwiftTest], skips: list[SkipRow]) -> list[str]:
    problems: list[str] = []
    by_file: dict[str, set[str]] = {}
    for test in tests:
        by_file.setdefault(test.file, set()).add(test.name)
    for file, mapping in PARTIAL.items():
        for name in mapping:
            if name not in by_file.get(file, set()):
                problems.append(f"内置映射多余: {file}#{name}")
    for file in WHOLE:
        if file not in by_file:
            problems.append(f"内置整文件映射找不到测试: {file}")
    for file in GLOBAL_SKIP_FILES:
        if file not in by_file:
            problems.append(f"全局 skip 文件找不到测试: {file}")
    global_rows = [row for row in skips if row.group == ""]
    global_keys = {row.test.key for row in global_rows}
    for test in tests:
        group = group_of(test)
        if test.file in GLOBAL_SKIP_FILES:
            if test.key not in global_keys:
                problems.append(f"全局 skip 缺行: {test.key}")
            if group is not None:
                problems.append(f"全局 skip 文件不应再归属组: {test.key}")
        else:
            if group is None:
                problems.append(f"未归属: {test.key}")
            if test.key in global_keys:
                problems.append(f"有组的测试不能写进全局 skip.tsv: {test.key}")
    seen: set[str] = set()
    inventory = {test.key for test in tests}
    for row in global_rows:
        if not row.reason.strip():
            problems.append(f"skip 理由为空: {row.test.key}")
        if "replaced-by:" in row.reason and not row.reason.split("replaced-by:", 1)[1].strip():
            problems.append(f"replaced-by 缺少目标: {row.test.key}")
        if row.test.key not in inventory:
            problems.append(f"skip 指向不存在的测试: {row.test.key}")
        if row.test.file not in GLOBAL_SKIP_FILES:
            problems.append(f"全局 skip.tsv 只收不移植的整文件: {row.test.key}")
        if row.test.key in seen:
            problems.append(f"全局 skip 重复: {row.test.key}")
        seen.add(row.test.key)
    return problems


def global_skip_problems(skips: list[SkipRow], cases: list[Case]) -> list[str]:
    problems: list[str] = []
    for row in skips:
        if row.group != "":
            continue
        if is_any_deferred(row.reason):
            problems.append(f"未消化的 deferred: {row.test.key} ({row.reason.strip()})")
            continue
        problems.extend(check_replaced_by(row, cases))
    return problems


def deferred_problems(skips: list[SkipRow], splits: dict[tuple[str, str, str], list[str]], cases: list[Case]) -> list[str]:
    problems: list[str] = []
    for row in skips:
        if not is_deferred(row.reason):
            continue
        consumed = False
        for other in skips:
            if other.group == "cross" and other.test == row.test and not is_any_deferred(other.reason):
                if other.reason.strip():
                    consumed = True
                    break
        ids = splits.get(("cross", row.test.file, row.test.name), [])
        if ids:
            matched = select_cases(cases, ids, row.test)
            ran = [case for case in matched if not case.skipped and not case.failed]
            if ran:
                consumed = True
        if not consumed:
            problems.append(f"deferred 未被 C9 消化: {row.test.key}")
    return problems


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="parity.py",
        description=(
            "核对 Swift 测试与 Kotlin JUnit 报告。"
            "按组时 deferred:C9 视为已登记；"
            "--all 时未被组 cross 消化的 deferred:C9 会失败。"
            "显式映射对应的执行数为 0 会失败；组内没有 skip 也没有 Kotlin 测试则是缺映射。"
        ),
    )
    parser.add_argument("--group", help="只核对这一组，例如 l0a、hls、cross")
    parser.add_argument(
        "--all",
        action="store_true",
        help="核对已开工的组，并要求 deferred:C9 已被 cross 消化",
    )
    parser.add_argument("--repo", type=Path, help="仓库根目录，默认按本文件位置推断")
    parser.add_argument("--parity-dir", type=Path, help="skip/split 表目录，默认 <repo>/android/parity")
    parser.add_argument(
        "--scope",
        type=Path,
        help="测试夹具用的组成员表（group、swift_file、swift_test）。给出后不再扫描 Tests/",
    )
    parser.add_argument(
        "--reports",
        type=Path,
        action="append",
        help="JUnit XML 目录，可重复。默认扫描 android/**/build/test-results",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.all == bool(args.group):
        print("必须且只能指定 --group 或 --all", file=sys.stderr)
        return 2
    root = (args.repo or repo_root_from_file()).resolve()
    parity_dir = (args.parity_dir or (root / "android" / "parity")).resolve()
    scope_mode = args.scope is not None
    scoped = load_scope(args.scope) if scope_mode else []
    if scope_mode:
        inventory = [test for _group, test in scoped]
        group_by_key = {test.key: group for group, test in scoped}
    else:
        inventory = parse_swift_tests(root)
        group_by_key = {}
        for test in inventory:
            assigned = group_of(test)
            if assigned is not None:
                group_by_key[test.key] = assigned
    if args.group and scope_mode and args.group not in {group for group, _test in scoped}:
        print(f"未知组: {args.group}", file=sys.stderr)
        return 2
    if args.group and not scope_mode and args.group not in GROUPS:
        print(f"未知组: {args.group}", file=sys.stderr)
        return 2
    skips = load_skips(parity_dir)
    splits = load_splits(parity_dir)
    comments = load_comment_links(root)
    if args.reports is not None:
        report_dirs = args.reports
    else:
        report_dirs = default_report_dirs(root)
    try:
        cases = load_cases(report_dirs)
    except SystemExit as exc:
        print(exc, file=sys.stderr)
        return 1
    problems: list[str] = []
    executed = 0
    if not scope_mode:
        problems.extend(structural_problems(inventory, skips))
    if args.all and scope_mode:
        groups = sorted({group for group, _test in scoped})
    elif args.all:
        groups = sorted(active_groups(parity_dir))
    else:
        groups = [args.group]
    for group in groups:
        owned = [test for test in inventory if group_by_key.get(test.key) == group]
        group_problems, group_executed = check_group(
            group, owned, skips, splits, comments, cases
        )
        problems.extend(group_problems)
        executed += group_executed
    if args.all:
        problems.extend(global_skip_problems(skips, cases))
        problems.extend(deferred_problems(skips, splits, cases))
    # 报告里已经失败、但没有挂到某条 Swift 测试上的用例也算失败。
    if any(case.failed for case in cases) and not any(p.startswith("测试失败:") for p in problems):
        for case in cases:
            if case.failed:
                problems.append(f"测试失败: {case.classname}#{case.name}")
                break
    print(f"executed={executed}")
    print(f"problems={len(problems)}")
    if problems:
        for item in problems:
            print(item, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
