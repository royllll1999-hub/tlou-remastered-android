"""Release 0.1.10: FEX disk code cache, parked copy waits, stream buffer memory choice."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "android-launcher/app/src/main/java/ro/cobrabm/fexdroid"
GPU = ROOT / "gow3_pc/gpu/shadps4/video_core"


class Release0110(unittest.TestCase):
    def test_fex_disk_cache_is_opt_in_with_its_own_threads(self):
        fex = (ROOT / "gow3_pc/cpu/guest_cpu_fex.cpp").read_text()
        self.assertIn('std::getenv("GOW3_FEX_DISK_CACHE")', fex)
        self.assertIn("FEXCore::Threads::Thread::SetInternalPointers", fex)
        self.assertIn("FEXCore::Config::CONFIG_DISKCACHEPATH", fex)
        self.assertIn('FEXCore::Config::Set(FEXCore::Config::CONFIG_DISKCACHE, "0");', fex)
        self.assertNotIn("disk_cache=unchanged", fex)
        settings = (JAVA / "PortSettings.java").read_text()
        self.assertIn('p.getBoolean("fex_disk_cache",false)', settings)
        game = (JAVA / "GameActivity.java").read_text()
        self.assertIn('env.put("GOW3_FEX_DISK_CACHE_DIR",new File(state,"fex-cache")', game)
        main = (JAVA / "MainActivity.java").read_text()
        self.assertIn('new File(getFilesDir(),"tlou/fex-cache")', main)

    def test_copy_waits_park_on_a_bounded_futex(self):
        park = (GPU / "renderer_vulkan/tlou_seq_park.h").read_text()
        self.assertIn("timespec timeout{0, 1'000'000};", park)
        self.assertIn('std::getenv("GOW3_PARK_COPY_WAITS")', park)
        scheduler_h = (GPU / "renderer_vulkan/vk_scheduler.h").read_text()
        self.assertIn("host_copy_park.Notify();", scheduler_h)
        self.assertEqual(scheduler_h.count("&host_copy_park)"), 2)
        batch = (GPU / "renderer_vulkan/tlou_host_copy_batch.h").read_text()
        self.assertIn("park->Notify();", batch)
        scheduler = (GPU / "renderer_vulkan/vk_scheduler.cpp").read_text()
        self.assertIn("Detail::ParkUntilSequence(host_copies_done, host_copies_issued", scheduler)
        self.assertIn('p.getBoolean("park_copy_waits",false)', (JAVA / "PortSettings.java").read_text())

    def test_stream_memory_choice(self):
        settings = (JAVA / "PortSettings.java").read_text()
        self.assertIn('env.put("GOW3_STREAM_MEMORY",', settings)
        buffer = (GPU / "buffer_cache/buffer.cpp").read_text()
        self.assertIn("GPU: stream buffer memory %s (GOW3_STREAM_MEMORY)", buffer)
        main = (JAVA / "MainActivity.java").read_text()
        self.assertIn('new String[]{"cached","device","host"},"cached"', main)


if __name__ == "__main__":
    unittest.main()
