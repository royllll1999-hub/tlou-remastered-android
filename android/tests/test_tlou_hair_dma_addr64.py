"""Hair/beard lighting fix: guest-memory reads for MUBUF ADDR64 loads only (GOW3_DMA_ADDR64).

v0.1.7 enabled GOW3_DIRECT_MEMORY_ACCESS for every dynamic ReadConst; at a cutscene start that
synchronized ~740 MB in 5 s and the job system deadlocked. The ADDR64 loads (hair, beard and
transparent shaders reading their shadow cascade matrices) are marked in the IR and are the only
ones the new switch sends through guest memory; the info pass and the SPIR-V backend must agree.
"""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
GPU = ROOT / "gow3_pc/gpu"


def read(path):
    return (GPU / path).read_text(encoding="utf-8")


class HairDmaAddr64(unittest.TestCase):
    def test_addr64_loads_are_marked(self):
        vm = read("shadps4/shader_recompiler/frontend/translate/vector_memory.cpp")
        self.assertIn("ir.ReadConstAddr64(base, ir.Imm32(i))", vm)
        emitter = read("shadps4/shader_recompiler/ir/ir_emitter.cpp")
        self.assertIn("Flags{u32(ReadConstAddr64Flag)}", emitter)
        self.assertIn("ReadConstAddr64Flag = 1u << 16", read("shadps4/shader_recompiler/ir/ir_emitter.h"))

    def test_info_pass_and_backend_use_the_same_rule(self):
        rule = "flags == IR::IREmitter::ReadConstAddr64Flag"
        info = read("shadps4/shader_recompiler/ir/passes/shader_info_collection_pass.cpp")
        spirv = read("shadps4/shader_recompiler/backend/spirv/emit_spirv_context_get_set.cpp")
        self.assertIn(rule, info)
        self.assertIn(rule, spirv)
        self.assertIn("flags & 0xFFFFu", spirv)
        self.assertIn("!EmulatorSettings.IsAddr64DmaEnabled()", info)

    def test_launcher_uses_the_narrow_switch(self):
        java = (ROOT / "android-launcher/app/src/main/java/ro/cobrabm/fexdroid/PortSettings.java").read_text(encoding="utf-8")
        self.assertIn('env.put("GOW3_DMA_ADDR64","1")', java)
        self.assertNotIn('env.put("GOW3_DIRECT_MEMORY_ACCESS","1")', java)


if __name__ == "__main__":
    unittest.main()
