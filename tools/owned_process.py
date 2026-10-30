"""Concurrent owned processes using the same bounded diagnostic collector."""
from concurrent.futures import ThreadPoolExecutor
import os
from pathlib import Path
import subprocess
import threading
import time
from runtime_diagnostics import run_monitored


class OwnedProcess:
    def __init__(self,command,*,cwd,log,timeout,diagnostic_command,metric_command=None,console_input=False):
        self.cwd=Path(cwd).resolve();self.log=Path(log).resolve()
        if self.log.parent!=self.cwd.parent or self.log.exists():
            raise ValueError('Group console alias must be a new file in the owned parent')
        self._stop=threading.Event();self._pool=ThreadPoolExecutor(max_workers=1)
        self._process=None;self._started=threading.Event()
        self._console=self.cwd/self.log.name
        self._future=self._pool.submit(run_monitored,command,cwd=self.cwd,log=self._console,
            timeout=timeout,diagnostics=self.cwd/(self.log.stem+'.diagnostics'),
            diagnostic_command=diagnostic_command,metric_command=metric_command,
            stop_requested=self._stop.is_set,stdin=subprocess.PIPE if console_input else None,
            process_started=self._publish)
        # A hard link exposes the very same live stdout to the group login
        # monitor while all receipt paths remain inside their native home.
        try:
            while not self._console.exists():
                if self._future.done():self._future.result()
                time.sleep(.01)
            os.link(self._console,self.log)
        except BaseException:
            self._stop.set()
            try:self._future.result(timeout=5)
            except BaseException:pass
            self._pool.shutdown(wait=False)
            raise
    def poll(self):
        if not self._future.done():return None
        try:return self._future.result().returncode
        except BaseException:return 1
    def _publish(self,process):self._process=process;self._started.set()
    @property
    def stdin(self):
        while not self._started.wait(.01):
            if self._future.done():self._future.result()
        return self._process.stdin
    @property
    def returncode(self):return self.poll()
    def terminate(self):self._stop.set()
    def kill(self):self._stop.set()
    def wait(self,timeout=None):
        from concurrent.futures import TimeoutError
        try:
            result=self._future.result(timeout=timeout)
            return result.returncode
        except TimeoutError as error:raise subprocess.TimeoutExpired(str(self.log),timeout) from error
        finally:
            if self._future.done():self._pool.shutdown(wait=True)


def native_process(command,*,cwd,log,timeout,console_input=False):
    java=Path(command[0]).resolve();metadata=java.parent.parent/'release'
    values=dict(line.split('=',1) for line in metadata.read_text().splitlines() if '=' in line)
    if values.get('SEMANTIC_VERSION')!='"16.0.2+7"' or values.get('IMPLEMENTOR')!='"Eclipse Foundation"':
        raise RuntimeError('Group runtime requires exact Temurin16.0.2+7')
    suffix='.exe' if java.suffix=='.exe' else ''
    stack=java.with_name('jstack'+suffix);metric=java.with_name('jstat'+suffix)
    if not stack.is_file() or not metric.is_file():raise FileNotFoundError('Missing pinned native JDK diagnostics')
    label=Path(log).stem
    launch=[command[0],'-Xlog:gc*,safepoint:file='+label+'.diagnostics/gc.txt:time,uptime,level,tags',*command[1:]]
    return OwnedProcess(launch,cwd=cwd,log=log,timeout=timeout,diagnostic_command=[str(stack),'-l'],metric_command=[str(metric),'-gc'],console_input=console_input)
