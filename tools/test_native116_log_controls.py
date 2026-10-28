"""Narrow diagnosis reproduced in both exact Forge36-only disposable worlds."""
from pathlib import Path
import tempfile,unittest
from audit_runtime_logs import audit

SHORT='[13:31:02] [main/ERROR] [minecraft/DimensionGeneratorSettings]: No key layers in MapLike[{}]; No key structures in MapLike[{}]; Not a registry ops\n'
LONG='[27Oct2026 13:31:02.312] [main/ERROR] [net.minecraft.world.gen.settings.DimensionGeneratorSettings/]: No key layers in MapLike[{}]; No key structures in MapLike[{}]; Not a registry ops\n'
class Native116LogControlTest(unittest.TestCase):
    def check(self,text,**options):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'console.log';path.write_text(text);return audit(path,**options)
    def test_only_exact_native_flat_settings_diagnostic_is_classified(self):
        for line in [SHORT,LONG]:
            with self.subTest(line=line):
                result=self.check(line,native=True)
                self.assertEqual(1,result['known_forge36_flat_settings_diagnostics'])
    def test_native_mode_is_required(self):
        for options in [{},{'development':True},{'upgrade':True}]:
            with self.subTest(options=options),self.assertRaises(RuntimeError):self.check(SHORT,**options)
    def test_changed_or_additional_errors_remain_fatal(self):
        for line in [SHORT.replace('MapLike[{}]','MapLike[{bad:1}]'),SHORT.replace('main/ERROR','Server thread/ERROR'),
                SHORT.replace('DimensionGeneratorSettings','AnotherCodec'),SHORT+'Caused by: java.lang.IllegalStateException: extra\n',
                SHORT+'[13:31:03] [main/ERROR] [minecraft/DimensionGeneratorSettings]: Actual settings were lost\n']:
            with self.subTest(line=line),self.assertRaises(RuntimeError):self.check(line,native=True)

if __name__=='__main__':unittest.main()
