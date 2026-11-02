"""Missing local logging inputs cannot become marker-only native qualification."""
import hashlib,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
from runtime_diagnostics import validate_logging_inputs
from owned_process import native_process
import native_runtime

class LoggingInputTest(unittest.TestCase):
    def test_real_xml_file_is_bound_by_exact_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'logging input.xml';path.write_text('<Configuration><Appenders/></Configuration>')
            self.assertEqual({str(path.resolve()):hashlib.sha256(path.read_bytes()).hexdigest().upper()},validate_logging_inputs(['java','-Dlog4j.configurationFile='+path.as_uri()]))
    def test_missing_or_empty_config_stops_before_a_native_group_launch(self):
        for empty in [False,True]:
            with self.subTest(empty=empty),tempfile.TemporaryDirectory() as directory:
                path=Path(directory)/'log4j2.xml'
                if empty:path.touch()
                with patch('owned_process.OwnedProcess') as launch,self.assertRaises(FileNotFoundError):
                    native_process(['java','-Dlog4j.configurationFile='+path.as_uri()],cwd=path.parent,log=path.parent/'run.log',timeout=240)
                launch.assert_not_called()
    def test_malformed_wrong_root_remote_or_duplicate_configs_are_fatal(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'log4j2.xml'
            for text in ['<Configuration>','<unrelated/>']:
                path.write_text(text)
                with self.assertRaises(ValueError):validate_logging_inputs(['java','-Dlog4j.configurationFile='+path.as_uri()])
            with self.assertRaises(ValueError):validate_logging_inputs(['java','-Dlog4j.configurationFile=https://example.invalid/log4j2.xml'])
            path.write_text('<Configuration/>');flag='-Dlog4j.configurationFile='+path.as_uri()
            with self.assertRaises(ValueError):validate_logging_inputs(['java',flag,flag])
    def test_marker_without_both_complete_native_log_copies_cannot_pass(self):
        import subprocess
        for missing in ['latest.log','debug.log']:
            with self.subTest(missing=missing),tempfile.TemporaryDirectory() as directory:
                home=Path(directory)
                def launch(*args,**kwargs):
                    (home/'world').mkdir();(home/'world/phase.pass').touch();(home/'phase.log').write_text('NATIVE_PASS\n')
                    for name in ['latest.log','debug.log']:
                        if name!=missing:(home/('phase.'+name)).write_text('native log\n')
                    return subprocess.CompletedProcess([],0)
                with patch.object(native_runtime,'run_java',side_effect=launch),self.assertRaisesRegex(FileNotFoundError,'complete native log'):
                    native_runtime.run_server(Path('java'),home,'phase',message='NATIVE_PASS')

if __name__=='__main__':unittest.main()
