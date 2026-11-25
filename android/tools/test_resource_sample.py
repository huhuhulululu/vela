import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import resource_sample


class AppCpuPercentTest(unittest.TestCase):
    def test_standard_line(self):
        text = "  12.5% 4321/vela.app: 10% user + 2.5% kernel"
        self.assertEqual(resource_sample.app_cpu_percent(text), 12.5)

    def test_total_only_is_none(self):
        self.assertIsNone(resource_sample.app_cpu_percent("  93% TOTAL: 40% user"))

    def test_total_line_does_not_shadow_app_line(self):
        text = "  93% TOTAL: 40% user + 53% kernel\n  12.5% 4321/vela.app: 10% user + 2.5% kernel"
        self.assertEqual(resource_sample.app_cpu_percent(text), 12.5)

    def test_other_process_is_none(self):
        text = "  5% 999/vela.app.test: 3% user + 2% kernel\n  93% TOTAL: 40% user"
        self.assertIsNone(resource_sample.app_cpu_percent(text))

    def test_empty_is_none(self):
        self.assertIsNone(resource_sample.app_cpu_percent(""))

    def test_integer_percent(self):
        self.assertEqual(
            resource_sample.app_cpu_percent("  7% 4321/vela.app: 7% user"), 7.0
        )


class StatTicksTest(unittest.TestCase):
    def test_comm_with_parentheses(self):
        tail = ["S"] + ["1"] * 10 + ["1000", "574"] + ["0"] * 5
        stat = "4321 (vela.app (main)) " + " ".join(tail)
        self.assertEqual(resource_sample.stat_ticks(stat), 1574)

    def test_plain_comm(self):
        tail = ["S"] + ["0"] * 10 + ["3", "4"] + ["0"] * 5
        stat = "4321 (vela.app) " + " ".join(tail)
        self.assertEqual(resource_sample.stat_ticks(stat), 7)

    def test_missing_fields_is_none(self):
        self.assertIsNone(resource_sample.stat_ticks("4321 (vela.app) S 1 2 3"))

    def test_empty_is_none(self):
        self.assertIsNone(resource_sample.stat_ticks(""))


class TickSummaryTest(unittest.TestCase):
    def test_prefix_stops_before_drop(self):
        rows = [
            {"cpu_ticks": 500, "t": 0},
            {"cpu_ticks": 1200, "t": 60},
            {"cpu_ticks": 2074, "t": 149},
            {"cpu_ticks": 126, "t": 154},
        ]
        prefix = resource_sample.monotonic_prefix(rows)
        self.assertEqual(len(prefix), 3)
        delta, seconds, ratio = resource_sample.tick_summary(rows)
        self.assertEqual(delta, 1574)
        self.assertEqual(seconds, 149)
        self.assertAlmostEqual(ratio, 1574 / 14900.0)
        self.assertAlmostEqual(ratio * 100.0, 10.6, places=1)

    def test_equal_ticks_stay_in_prefix(self):
        rows = [{"cpu_ticks": 100, "t": 0}, {"cpu_ticks": 100, "t": 10}]
        delta, seconds, ratio = resource_sample.tick_summary(rows)
        self.assertEqual(delta, 0)
        self.assertEqual(seconds, 10)
        self.assertEqual(ratio, 0.0)

    def test_missing_ticks_breaks_prefix(self):
        rows = [
            {"cpu_ticks": 100, "t": 0},
            {"cpu_ticks": None, "t": 5},
            {"cpu_ticks": 300, "t": 10},
        ]
        self.assertEqual(len(resource_sample.monotonic_prefix(rows)), 1)
        self.assertEqual(resource_sample.tick_summary(rows), (None, None, None))

    def test_too_few_rows(self):
        self.assertEqual(resource_sample.tick_summary([]), (None, None, None))
        self.assertEqual(
            resource_sample.tick_summary([{"cpu_ticks": 5, "t": 0}]), (None, None, None)
        )

    def test_zero_seconds_has_no_ratio(self):
        rows = [{"cpu_ticks": 100, "t": 7}, {"cpu_ticks": 200, "t": 7}]
        delta, seconds, ratio = resource_sample.tick_summary(rows)
        self.assertEqual(delta, 100)
        self.assertEqual(seconds, 0)
        self.assertIsNone(ratio)

    def test_custom_hz(self):
        rows = [{"cpu_ticks": 0, "t": 0}, {"cpu_ticks": 250, "t": 10}]
        delta, seconds, ratio = resource_sample.tick_summary(rows, hz=250)
        self.assertEqual(delta, 250)
        self.assertEqual(seconds, 10)
        self.assertAlmostEqual(ratio, 0.1)


if __name__ == "__main__":
    unittest.main()
