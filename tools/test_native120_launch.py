import subprocess
import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from types import SimpleNamespace
import native_runtime


class Native120LaunchTests(unittest.TestCase):
    def test_posix_native_diagnostics_fixture_uses_the_posix_tools(self):
        with patch.object(native_runtime,'os',SimpleNamespace(name='posix')):
            self.test_packaged_server_uses_installer_module_arguments_and_java25_gc()

    def jdk(self, root, version='25.0.3+9-LTS'):
        suffix='.exe' if native_runtime.os.name=='nt' else ''
        java = root/('jdk/bin/java'+suffix)
        java.parent.mkdir(parents=True)
        for name in ('java', 'jstack', 'jstat'):
            (java.parent/(name+suffix)).touch()
        (java.parent.parent/'release').write_text(
            'JAVA_RUNTIME_VERSION="'+version+'"\nIMPLEMENTOR="Eclipse Adoptium"\n')
        return java

    def test_packaged_server_uses_installer_module_arguments_and_java25_gc(self):
        with tempfile.TemporaryDirectory() as directory:
            home=Path(directory); java=self.jdk(home)
            with patch('native_runtime.run_monitored', return_value=subprocess.CompletedProcess([],0)) as run:
                native_runtime.run_java(java,home,'fixture',timeout=240)
            command=run.call_args.args[0]
            self.assertNotIn('-jar',command)
            self.assertIn('-Xmx2G',command)
            self.assertTrue(any(arg.startswith('@libraries/net/minecraftforge/forge/26.2-65.1.5/') for arg in command))
            gc=next((arg for arg in command if arg.startswith('-Xlog:')),None)
            self.assertIsNotNone(gc)
            self.assertIn('gc*,safepoint',gc)
            self.assertFalse(any('PrintGC' in arg or 'Xloggc' in arg for arg in command))
            self.assertEqual(240,run.call_args.kwargs['timeout'])
            suffix='.exe' if native_runtime.os.name=='nt' else ''
            self.assertEqual([str(java.with_name('jstack'+suffix)),'-l'],run.call_args.kwargs['diagnostic_command'])
            self.assertEqual([str(java.with_name('jstat'+suffix)),'-gc'],run.call_args.kwargs['metric_command'])

    def test_runtime_rejects_a_different_jdk_before_launch(self):
        with tempfile.TemporaryDirectory() as directory:
            home=Path(directory); java=self.jdk(home,'17.0.1+12')
            with patch('native_runtime.run_monitored') as run:
                with self.assertRaisesRegex(RuntimeError,'25.0.3'):
                    native_runtime.run_java(java,home,'fixture')
                run.assert_not_called()

    def test_startup_diagnostics_wait_for_the_owned_jvm_to_load(self):
        with tempfile.TemporaryDirectory() as directory:
            home=Path(directory)
            java=self.jdk(home)
            gc=home/'fixture.diagnostics/gc.txt'
            started=[]
            def launch(command, **options):
                self.assertEqual(240,options['timeout'])
                options['diagnostics'].mkdir()
                options['process_started'](SimpleNamespace(pid=1234,poll=lambda:None))
                return subprocess.CompletedProcess(command,0)
            def boot(_):
                gc.write_text('[0.010s][info][gc,init] Version: 25.0.3+9-LTS\n')
            def attach(command, **options):
                self.assertTrue(gc.is_file(),'Attach ran before the owned JVM initialized GC logging')
                self.assertEqual('1234',command[-1])
                started.append(Path(command[0]).stem)
                options['stdout'].write('attached\n')
            with patch('native_runtime.run_monitored',side_effect=launch), \
                    patch('time.sleep',side_effect=boot),patch('native_runtime.subprocess.run',side_effect=attach):
                native_runtime.run_java(java,home,'fixture',timeout=240)
            self.assertEqual(['jstack','jstat'],started)

    def test_server_inputs_pin_native50_instead_of_the_old_launcher(self):
        self.assertEqual('forge-26.2-65.1.5',native_runtime.FORGE)
        for path in ('libraries/net/minecraftforge/forge/26.2-65.1.5/win_args.txt',
                     'libraries/net/minecraftforge/forge/26.2-65.1.5/unix_args.txt',
                     'libraries/net/minecraftforge/forge/26.2-65.1.5/forge-26.2-65.1.5-server.jar'):
            self.assertIn(path,native_runtime.SERVER_INPUTS)
        self.assertFalse(any('1.16.5' in path for path in native_runtime.SERVER_INPUTS))

    def test_native50_historical_workers_have_their_own_fresh_qualification_binding(self):
        proof=native_runtime.WORKER_BUDGET_PROOF
        self.assertEqual(('26.2','65.1.5',200,4,True),tuple(proof[key] for key in
            ('minecraft','forge','reloads','jvms','qualified')))
        self.assertIn('server_input_manifest_sha256',proof)
        self.assertRegex(proof['qualification_evidence_sha256'],r'^[A-F0-9]{64}$')
        source=Path(__file__).resolve().parents[1]/'src/controlTest/java/buildcontrol/ResourceControl.java'
        self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest().upper(),proof['controller_sha256'])
        self.assertEqual((),native_runtime.HISTORICAL_JVM_ARGS)
        self.assertEqual([],proof['jvm_args'])

    def test_owned_server_does_not_trigger_native_bare_json_flat_settings_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); sdk=root/'sdk';(sdk/'libraries').mkdir(parents=True)
            artifact=sdk/'libraries/pinned-fixture';artifact.write_bytes(b'owned-sdk-input')
            shim=sdk/(native_runtime.FORGE+'-shim.jar');shim.write_bytes(b'owned-shim')
            pins={'libraries/pinned-fixture':hashlib.sha256(artifact.read_bytes()).hexdigest().upper(),
                  shim.name:hashlib.sha256(shim.read_bytes()).hexdigest().upper()}
            with patch.object(native_runtime,'SERVER_INPUTS',pins),patch('native_runtime.subprocess.run'):
                native_runtime.prepare_server(root/'home',sdk)
            self.assertEqual(shim.read_bytes(),(root/'home'/shim.name).read_bytes())
            properties=(root/'home/server.properties').read_text()
            self.assertIn('level-type=minecraft\\:normal\n',properties)
            self.assertNotIn('level-type=FLAT',properties)
            self.assertFalse((root/'home/world').exists(),'Historical/native-codec worlds are copied separately')


if __name__=='__main__':unittest.main()
