"""01.11 job-wake patch: structure checks that need no game file."""
from pathlib import Path
import struct
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import tlou_patches as patches


class JobWake111Test(unittest.TestCase):
    def test_only_with_sleep_on_111(self):
        self.assertEqual(set(patches.job_wake_for({}, "01.11")), {"job-wake"})
        self.assertEqual(patches.job_wake_for({"job_wake": False}, "01.11"), {})
        self.assertEqual(patches.job_wake_for({"job_sleep": False}, "01.11"), {})
        self.assertEqual(patches.job_wake_for({}, "01.00"), {})
        with self.assertRaises(ValueError):
            patches.job_wake_for({"job_wake": "yes"}, "01.11")

    def test_writes_fit_and_calls_reach_the_thunk(self):
        cave = patches.JOB_WAKE_CAVE_111
        changes = patches.JOB_WAKE_111["job-wake"]
        for at, original, replacement in changes:
            if replacement:
                self.assertEqual(len(bytes.fromhex(replacement)), len(bytes.fromhex(original)), hex(at))
        calls = [(at, bytes.fromhex(r)) for at, o, r in changes if r and o.startswith("e8")]
        self.assertEqual(len(calls), 2)
        for at, code in calls:
            self.assertEqual(at + 5 + struct.unpack("<i", code[1:5])[0], cave)
        thunk = bytes.fromhex(next(r for at, _, r in changes if at == cave))
        # call CondBroadcast wrapper, then jmp to the original callee
        self.assertEqual(cave + 16 + 5 + struct.unpack("<i", thunk[17:21])[0], 0xC81DD0)
        self.assertEqual(thunk[-5], 0xE9)
        self.assertEqual(cave + len(thunk) + struct.unpack("<i", thunk[-4:])[0], 0xC89BD0)
        # every push has its pop: rax rcx rdx rsi rdi r8 r9
        self.assertEqual(thunk[:9], bytes.fromhex("505152565741504151"))
        self.assertEqual(thunk[21:30], bytes.fromhex("415941585f5e5a5958"))

    def test_no_overlap_with_other_111_patches(self):
        from itertools import chain
        mine = [(a, len(bytes.fromhex(o))) for a, o, _ in patches.JOB_WAKE_111["job-wake"]]
        others = [(a, len(bytes.fromhex(o))) for a, o, _ in chain(
            *patches.BASE_PATCHES_111.values(), *patches.PERFORMANCE_EFFECTS_111.values(),
            patches.scene_aim_resolution_patches_111("720"))]
        for a, n in mine:
            for b, m in others:
                self.assertTrue(a + n <= b or b + m <= a, (hex(a), hex(b)))


if __name__ == "__main__":
    unittest.main()
