"""Release 0.1.9: 900p/828p (01.11), SGSR, 40 FPS at 120 Hz, crash logs, render profile."""
from pathlib import Path
import struct
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "android"))
import tlou_patches as patches  # noqa: E402

JAVA = ROOT / "android-launcher/app/src/main/java/ro/cobrabm/fexdroid"
GPU = ROOT / "gow3_pc/gpu"
KNOWN = "3cfa475f88ca68485b6b9230e62b84e195cdbe19e6f7295c110b8dcf8f20884e"


class Release019(unittest.TestCase):
    def test_900p_and_828p_are_whole_111_profiles(self):
        self.assertEqual(patches.LOW_RES_SCENE_111["900"], (1600, 900))
        self.assertEqual(patches.LOW_RES_SCENE_111["828"], (1472, 828))
        for name in ("900", "828"):
            width, height = patches.LOW_RES_SCENE_111[name]
            # Quarter and half sizes the patched tables hold must be whole.
            self.assertEqual(width % 8, 0)
            self.assertEqual(height % 4, 0)
            self.assertEqual(height % 2, 0)
            changes = patches.selected_options({"resolution": name}, "01.11", KNOWN)["resolution-" + name]
            self.assertEqual({a for a, _, _ in changes}, set(patches.RESOLUTION_111_GUARDS))
            by_addr = {a: bytes.fromhex(after) for a, _, after in changes}
            self.assertEqual(struct.unpack("<4I", by_addr[0x116BF10]),
                             (width, height, width // 8, height // 4))
            self.assertEqual(by_addr[0xAD47E7],
                             b"\xb9" + struct.pack("<I", width // 2) + b"\x41\xb8" + struct.pack("<I", height // 2))
        # 810p would not divide: 810 / 4 = 202.5.
        self.assertNotEqual(810 % 4, 0)

    def test_111_only_resolutions_are_refused_on_100(self):
        for name in ("900", "828"):
            with self.assertRaisesRegex(ValueError, "01.11"):
                patches.selected_options({"resolution": name}, "01.00")
        self.assertNotIn("900", patches.LOW_RES_SCENE)
        source = (ROOT / "android/tlou_patches.py").read_text()
        self.assertIn("900p e 828p existem somente na versão 01.11; usando Original", source)

    def test_launcher_offers_the_new_resolutions_and_scene_sizes(self):
        settings = (JAVA / "PortSettings.java").read_text()
        self.assertIn('case "900": return "1600x900";', settings)
        self.assertIn('case "828": return "1472x828";', settings)
        for name in ("MainActivity.java", "PortMenu.java"):
            text = (JAVA / name).read_text()
            self.assertIn('new String[]{"original","900","828","720","540","360"}', text)
            self.assertIn('new String[]{"30","40","60"}', text)
            self.assertIn('new String[]{"fsr","sgsr"}', text)

    def test_sgsr_pass_is_built_and_selected(self):
        cmake = (GPU / "CMakeLists.txt").read_text()
        self.assertIn("add_host_shader(sgsr.comp sgsr_comp)", cmake)
        shader = (GPU / "shadps4/video_core/host_shaders/sgsr.comp").read_text()
        self.assertIn("SPDX-License-Identifier: BSD-3-Clause", shader)
        self.assertIn("layout(local_size_x = 16, local_size_y = 16) in;", shader)
        self.assertIn("rgba16f", shader)
        fsr = (GPU / "shadps4/video_core/renderer_vulkan/host_passes/fsr_pass.cpp").read_text()
        self.assertIn('#include "video_core/host_shaders/sgsr_comp.h"', fsr)
        self.assertIn("sgsr ? sgsr_pipeline.get() : easu_pipeline.get()", fsr)
        presenter = (GPU / "shadps4/video_core/renderer_vulkan/vk_presenter.cpp").read_text()
        self.assertIn('std::getenv("GOW3_SPATIAL_UPSCALER")', presenter)
        settings = (JAVA / "PortSettings.java").read_text()
        self.assertIn('env.put("GOW3_SPATIAL_UPSCALER","sgsr");', settings)
        # GOW3_UPSCALER stays the (off) temporal upscaler switch.
        self.assertIn('env.put("GOW3_UPSCALER","off");', settings)

    def test_40_fps_requests_120_hz(self):
        settings = (JAVA / "PortSettings.java").read_text()
        self.assertIn('return "30".equals(limit)||"40".equals(limit)?limit:"60";', settings)
        game = (JAVA / "GameActivity.java").read_text()
        self.assertIn("requestHighRefresh();", game)
        self.assertIn("attrs.preferredDisplayModeId=best.getModeId();", game)

    def test_crashed_runs_keep_dated_logs_in_the_export(self):
        log = (JAVA / "RunLog.java").read_text()
        self.assertIn('"yyyy-MM-dd_HH-mm-ss"', log)
        self.assertIn('zip.putNextEntry(new ZipEntry("tlou/crashes/"+file.getName()));', log)
        game = (JAVA / "GameActivity.java").read_text()
        self.assertIn("RunLog.archiveCrash(this,message);", game)
        self.assertIn('RunLog.archiveCrash(activity,"salvo pelo menu do jogo");', (JAVA / "PortMenu.java").read_text())

    def test_render_profile_switch(self):
        settings = (JAVA / "PortSettings.java").read_text()
        self.assertIn('env.put("GOW3_GPU_PROFILE","1");', settings)
        self.assertIn('env.put("GOW3_FRAME_STATS","1");', settings)


if __name__ == "__main__":
    unittest.main()
