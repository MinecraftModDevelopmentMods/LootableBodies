"""Retain startup evidence before terminating an owned native JVM.

The runtime deadline never moves. Attaching a diagnostic tool is bounded by
that same deadline, and a failed capture can never turn a timeout into a pass.
"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import time


def run_monitored(command, *, cwd, log, timeout, diagnostics,
                  diagnostic_command, metric_command=None, stop_requested=None,
                  stdin=None, process_started=None):
    if timeout <= 0:
        raise ValueError('Runtime deadline must be positive')
    cwd, log, diagnostics = Path(cwd), Path(log), Path(diagnostics)
    if log.exists():
        raise FileExistsError(log)
    diagnostics.mkdir(exist_ok=False)
    started = time.monotonic()
    deadline = started + timeout
    record = dict(command=[str(p) for p in command], cwd=str(cwd), timeout=timeout,
                  at=datetime.now(timezone.utc).isoformat(), pid=None,
                  captures=[], metrics=[], timed_out=False, returncode=None)
    process = collector = collector_output = capture = None
    capture_deadline = deadline
    pending_metric = False
    due = iter(timeout*fraction for fraction in (.25, .5, .75, .9375))
    next_capture = next(due)

    def save():
        pending = diagnostics/'result.pending'
        pending.write_text(json.dumps(record, indent=2)+'\n', encoding='utf-8')
        pending.replace(diagnostics/'result.json')

    def end_collector(reason=None):
        nonlocal collector, collector_output, capture, pending_metric
        if collector is None:
            return
        if collector.poll() is None:
            collector.kill()
            capture['stopped_reason'] = reason
            capture['timed_out'] = reason in ('capture_deadline', 'runtime_deadline')
        capture['returncode'] = collector.wait()
        capture['finished_seconds'] = time.monotonic()-started
        collector_output.close()
        pending_metric = capture['kind'] == 'threads' and metric_command is not None and reason is None
        collector = collector_output = capture = None
        save()

    def launch_collector(kind, command, now):
        nonlocal collector, collector_output, capture, capture_deadline, pending_metric
        collection = record['captures' if kind == 'threads' else 'metrics']
        filename = kind+'-%02d.txt' % (len(collection)+1)
        capture = dict(file=filename, kind=kind, started_seconds=now-started,
                       timed_out=False, returncode=None)
        collection.append(capture)
        capture['log_bytes'] = {
            str(path.relative_to(cwd)): path.stat().st_size
            for path in (log, cwd/'logs/latest.log', cwd/'logs/debug.log')
            if path.is_file()}
        collector_output = (diagnostics/filename).open('x', encoding='utf-8')
        try:
            collector = subprocess.Popen(
                [*command, str(process.pid)], cwd=cwd,
                stdout=collector_output, stderr=subprocess.STDOUT)
            capture['pid'] = collector.pid
            capture_deadline = min(deadline, now+min(10, timeout*.2))
        except OSError as error:
            collector_output.write(str(error)+'\n')
            collector_output.close()
            capture['launch_error'] = str(error)
            pending_metric = kind == 'threads' and metric_command is not None
            collector_output = capture = None
        save()

    save()
    try:
        with log.open('x', encoding='utf-8') as output:
            process = subprocess.Popen(command, cwd=cwd, stdout=output,
                                       stderr=subprocess.STDOUT, stdin=stdin,
                                       text=stdin is not None)
            record['pid'] = process.pid
            save()
            if process_started is not None:process_started(process)
            while process.poll() is None:
                now = time.monotonic()
                if stop_requested is not None and stop_requested():
                    record['stopped_reason'] = 'owned_group_abort'
                    end_collector('owned_group_abort')
                    process.terminate()
                    try:
                        process.wait(timeout=min(2,max(.01,deadline-now)))
                    except subprocess.TimeoutExpired:
                        process.kill();process.wait()
                    break
                if now >= deadline:
                    record['timed_out'] = True
                    end_collector('runtime_deadline')
                    process.kill()
                    process.wait()
                    break
                if collector is not None:
                    if collector.poll() is not None:
                        end_collector()
                    elif now >= capture_deadline:
                        end_collector('capture_deadline')
                if pending_metric and collector is None:
                    pending_metric = False
                    launch_collector('heap-gc', metric_command, now)
                elif now-started >= next_capture and collector is None:
                    launch_collector('threads', diagnostic_command, now)
                    next_capture = next(due, float('inf'))
                # Never wait on the collector in the runtime's deadline path.
                process_wait = min(.1, max(0, deadline-time.monotonic()))
                try:
                    process.wait(timeout=process_wait)
                except subprocess.TimeoutExpired:
                    pass
    finally:
        try:
            end_collector('runtime_deadline' if record['timed_out'] else 'runtime_exit')
        finally:
            # Evidence I/O can fail too. Reap the runtime even if saving the
            # collector's result fails while unwinding an earlier exception.
            if process is not None:
                if process.poll() is None:
                    process.kill()
                record['returncode'] = process.wait()
        record['elapsed_seconds'] = time.monotonic()-started
        record['evidence'] = {
            p.name: hashlib.sha256(p.read_bytes()).hexdigest().upper()
            for p in diagnostics.iterdir() if p.is_file() and p.name != 'result.json'}
        save()
    if record['timed_out']:
        raise subprocess.TimeoutExpired(command, timeout)
    return subprocess.CompletedProcess(command, record['returncode'])
