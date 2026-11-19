"""Release 0.1.10 leftovers after the v0.2.0 AOT merge: stream buffer memory choice."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "android-launcher/app/src/main/java/ro/cobrabm/fexdroid"
GPU = ROOT / "gow3_pc/gpu/shadps4/video_core"


class Release0110(unittest.TestCase):
    def test_fex_era_switches_are_retired_on_native_aot(self):
        # v0.2.0 runs guest code as ARM64 AOT: the FEX disk cache and the
        # parked copy waits (upstream parks host-copy waits itself) are gone.
        settings = (JAVA / "PortSettings.java").read_text()
        for key in ("fex_disk_cache", "park_copy_waits", "pause_wfe", "fex_no_tso"):
            self.assertIn('"%s"' % key, settings)  # listed in retired prefs
        self.assertNotIn("GOW3_FEX_DISK_CACHE", settings)
        self.assertNotIn("GOW3_PARK_COPY_WAITS", settings)
        self.assertFalse((GPU / "renderer_vulkan/tlou_seq_park.h").exists())

    def test_stream_memory_choice(self):
        settings = (JAVA / "PortSettings.java").read_text()
        self.assertIn('env.put("GOW3_STREAM_MEMORY",', settings)
        buffer = (GPU / "buffer_cache/buffer.cpp").read_text()
        self.assertIn("GPU: stream buffer memory %s (GOW3_STREAM_MEMORY)", buffer)
        main = (JAVA / "MainActivity.java").read_text()
        self.assertIn('new String[]{"cached","device","host"},"cached"', main)


if __name__ == "__main__":
    unittest.main()
