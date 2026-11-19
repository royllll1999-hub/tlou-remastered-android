"""Odin 3 fork, release 0.2.0: native output switch and sharpness for FSR/SGSR."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "android-launcher/app/src/main/java/ro/cobrabm/fexdroid"
VC = ROOT / "gow3_pc/gpu/shadps4/video_core"


class OdinOutputAndSharpness(unittest.TestCase):
    def test_output_mode_defaults_to_native_display(self):
        main = (JAVA / "MainActivity.java").read_text()
        self.assertIn('choiceRow(image,"output_mode","Resolução de saída"', main)
        self.assertIn('new String[]{"native","720"},"native"', main)
        game = (JAVA / "GameActivity.java").read_text()
        self.assertIn('getString("output_mode","native")', game)
        policy = (JAVA / "TlouDevicePolicy.java").read_text()
        self.assertIn('if(!"720".equals(mode))', policy)

    def test_one_sharpness_drives_rcas_and_sgsr(self):
        for name in ("MainActivity.java", "PortMenu.java"):
            self.assertIn('new String[]{"25","50","75","90","100"},"75"', (JAVA / name).read_text())
        settings = (JAVA / "PortSettings.java").read_text()
        self.assertIn('"100".equals(value)', settings)
        shader = (VC / "host_shaders/sgsr.comp").read_text()
        self.assertIn("uintBitsToFloat(Const2.x)", shader)
        fsr = (VC / "renderer_vulkan/host_passes/fsr_pass.cpp").read_text()
        self.assertIn("&settings.sgsr_edge_sharpness", fsr)
        presenter = (VC / "renderer_vulkan/vk_presenter.cpp").read_text()
        self.assertIn("Gow3Graphics::SgsrEdgeSharpness(graphics.rcas_strength)", presenter)

    def test_translations_have_russian(self):
        tsv = (JAVA.parents[3] / "assets/ui-translations.tsv").read_text(encoding="utf-8")
        for ru in ("Выходное разрешение", "Резкость (FSR и SGSR)", "Максимальная"):
            self.assertIn(ru, tsv)


if __name__ == "__main__":
    unittest.main()
