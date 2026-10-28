"""Only observed pre-compilation Mavenizer transport failures may retry."""
from pathlib import Path
import subprocess,sys,tempfile,unittest
from unittest.mock import patch
from command_logs import run_build_input_logged

RESET="""Caused by: java.lang.RuntimeException: Failed to execute task `merge`
 at net.minecraftforge.mcmaven.impl.util.Task$Simple.execute(Task.java:151)
Caused by: java.net.SocketException: Connection reset
 at net.minecraftforge.util.download.DownloadUtils.downloadFile(DownloadUtils.java:94)
A problem occurred configuring root project 'LootableBodies'.
"""
TIMEOUT=RESET.replace('java.net.SocketException: Connection reset','java.net.http.HttpTimeoutException: request timed out')

class BuildInputRetryTest(unittest.TestCase):
    def execute(self, folder, logs, timeout=False):
        path=Path(folder)/'build.log';calls=[]
        def run(command,**options):
            index=len(calls);calls.append(command);options['stdout'].write(logs[index]);options['stdout'].flush()
            if timeout:raise subprocess.TimeoutExpired(command,1)
            if logs[index]!='BUILD SUCCESSFUL\n':raise subprocess.CalledProcessError(1,command)
            return subprocess.CompletedProcess(command,0)
        with patch('command_logs.subprocess.run',side_effect=run),patch('command_logs.time.sleep'):
            result=run_build_input_logged(['gradlew','classes'],path=path,cwd=folder)
        return result,calls,path

    def test_each_observed_transport_failure_retries_and_retains_full_failed_evidence(self):
        for failed in [RESET,TIMEOUT]:
            with self.subTest(failed=failed),tempfile.TemporaryDirectory() as folder:
                result,calls,path=self.execute(folder,[failed,'BUILD SUCCESSFUL\n'])
                self.assertEqual(0,result.returncode);self.assertEqual(2,len(calls))
                self.assertEqual(failed,path.with_name('build.attempt-1.log').read_text())
                self.assertEqual('BUILD SUCCESSFUL\n',path.read_text())

    def test_three_failed_attempts_stop_and_keep_every_log(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(subprocess.CalledProcessError):self.execute(folder,[RESET]*3)
            for index in range(1,4):self.assertEqual(RESET,(Path(folder)/('build.attempt-'+str(index)+'.log')).read_text())

    def test_compiler_runtime_checksum_permission_and_unknown_failures_stop_immediately(self):
        cases=['error: incompatible types\n',RESET+'\n> Task :compileJava FAILED\n',
            RESET+'\n[Server thread/ERROR]: inventory mismatch\n',RESET+'\nChecksum mismatch\n',
            RESET+'\nHTTP 403 Forbidden\n',RESET+'\nPermission denied\n',
            RESET+'\nHTTP response code: 429\n',RESET+'\nstatus code: 403\n',
            RESET+'\nExecution failed for task :compileJava\n',
            RESET.replace('net.minecraftforge.util.download.DownloadUtils','example.Unrelated')]
        for failed in cases:
            with self.subTest(failed=failed),tempfile.TemporaryDirectory() as folder:
                with self.assertRaises(subprocess.CalledProcessError):self.execute(folder,[failed])
                self.assertEqual(1,len(list(Path(folder).glob('build.attempt-*.log'))))

    def test_process_deadline_does_not_retry_even_when_output_mentions_a_transport_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(subprocess.TimeoutExpired):self.execute(folder,[RESET],timeout=True)
            self.assertEqual(1,len(list(Path(folder).glob('build.attempt-*.log'))))

    def test_existing_attempt_logs_are_never_replaced(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'build.attempt-1.log';path.write_text('Earlier evidence\n')
            self.execute(folder,['BUILD SUCCESSFUL\n'])
            self.assertEqual('Earlier evidence\n',path.read_text())
            self.assertEqual('BUILD SUCCESSFUL\n',path.with_name('build.attempt-2.log').read_text())

    def test_existing_canonical_log_is_preserved_before_another_invocation(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'build.log';path.write_text('Previous complete failure\n')
            self.execute(folder,['BUILD SUCCESSFUL\n'])
            self.assertEqual('Previous complete failure\n',path.with_name('build.previous-1.log').read_text())

    def test_cli_keeps_arguments_and_retries_a_real_child_process(self):
        with tempfile.TemporaryDirectory() as folder:
            home=Path(folder);counter=home/'counter with spaces.txt';log=home/'build.log'
            script='from pathlib import Path;import sys;p=Path(sys.argv[1]);n=int(p.read_text())+1 if p.exists() else 1;p.write_text(str(n));print(sys.argv[2] if n==1 else "BUILD SUCCESSFUL");sys.exit(1 if n==1 else 0)'
            result=subprocess.run([sys.executable,str(Path(__file__).with_name('command_logs.py')),
                '--log',str(log),'--',sys.executable,'-c',script,str(counter),RESET],
                capture_output=True,text=True,timeout=40)
            self.assertEqual(0,result.returncode,result.stderr)
            self.assertEqual('2',counter.read_text())
            self.assertIn(RESET,log.with_name('build.attempt-1.log').read_text())
            self.assertEqual('BUILD SUCCESSFUL\n',log.read_text())

if __name__=='__main__':unittest.main()
