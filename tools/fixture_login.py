"""Bounded retries for a Forge-only pre-login failure, with immutable log receipts."""
import hashlib
import json
import re
from pathlib import Path

FAILURE = re.compile(r"^\[\d{2}:\d{2}:\d{2}\] \[Server thread/INFO\] \[(?:minecraft/NetHandlerPlayServer|net\.minecraft\.network\.NetHandlerPlayServer)\]: Container([AB]) lost connection: Internal Exception: io\.netty\.handler\.codec\.EncoderException: java\.io\.IOException: Can't serialize unregistered packet$")
JOIN = re.compile(r'^\[\d{2}:\d{2}:\d{2}\] \[Server thread/INFO\] \[(?:minecraft/DedicatedServer|net\.minecraft\.server\.dedicated\.DedicatedServer)\]: Container([AB]) (joined|left) the game$')
LIMIT = 3

class LoginMonitor:
    def __init__(self, home):
        self.home=Path(home);self.seen=0;self.counts={'A':0,'B':0}
    def poll(self):
        text=(self.home/'server.log').read_text(encoding='utf-8',errors='replace')
        lines=text[:text.rfind('\n')+1].splitlines()
        for line in lines[self.seen:]:
            joined=JOIN.fullmatch(line)
            if joined and joined[1]=='A' and joined[2]=='joined':
                (self.home/'sync/client-A-joined').touch()
            match=FAILURE.fullmatch(line)
            if match:
                role=match[1];self.counts[role]+=1
                if self.counts[role]>LIMIT:raise RuntimeError('Forge login retry limit: '+role)
                (self.home/'sync'/('login-retry-'+role)).write_text(str(self.counts[role]))
            elif 'lost connection: Internal Exception:' in line:
                raise RuntimeError('Unrecognised connection failure: '+line)
        self.seen=len(lines)

def start_clients(start):
    """Boot both clients together; the client B controller waits for A's join marker."""
    start('clientA');start('clientB')


def receipt_lines(path, text):
    receipt=path.with_name(path.name+'.login-retries.json')
    if not receipt.is_file():return set()
    data=json.loads(receipt.read_text(encoding='utf-8'))
    if data.get('schema')!=1 or data.get('sha256')!=hashlib.sha256(path.read_bytes()).hexdigest():
        raise RuntimeError('Invalid or stale Forge login receipt: '+str(path))
    lines=text.splitlines();allowed=set(data['lines']);counts={'A':0,'B':0};online=set();pending=set();actual=set()
    for i,line in enumerate(lines):
        join=JOIN.fullmatch(line)
        if join:
            role=join[1]
            if join[2]=='joined':online.add(role);pending.discard(role)
            else:online.discard(role)
        match=FAILURE.fullmatch(line)
        if match:
            role=match[1];counts[role]+=1;pending.add(role);actual.add(i)
            if role in online or counts[role]>LIMIT:raise RuntimeError('Failure outside pre-login retry scope')
    if not actual or pending or actual!=allowed:raise RuntimeError('Unrecovered or incomplete Forge login receipt')
    return allowed

def write_receipts(home):
    home=Path(home)
    for path in [home/'server.log',home/'server/logs/latest.log',home/'server/logs/debug.log']:
        if not path.is_file():continue
        text=path.read_text(encoding='utf-8',errors='replace');lines=text.splitlines()
        allowed=[i for i,line in enumerate(lines) if FAILURE.fullmatch(line)]
        if not allowed:continue
        counts={'A':0,'B':0}
        for i in allowed:counts[FAILURE.fullmatch(lines[i])[1]]+=1
        for role,count in counts.items():
            used=home/'sync'/('login-retried-'+role)
            if count and (not used.is_file() or used.read_text().strip()!=str(count)):
                raise RuntimeError('Unconsumed Forge login retry: '+role)
        receipt=path.with_name(path.name+'.login-retries.json')
        receipt.write_text(json.dumps({'schema':1,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'lines':allowed},indent=2)+'\n')
        try:receipt_lines(path,text)
        except Exception:
            receipt.unlink();raise

def archive_live_logs(home):
    """Preserve each first-process log and receipt before Forge replaces latest.log."""
    home=Path(home)
    for name in ('latest.log','debug.log'):
        source=home/'server/logs'/name
        if not source.is_file():continue
        target=home/('server-live-'+name)
        receipt=source.with_name(source.name+'.login-retries.json')
        target_receipt=target.with_name(target.name+'.login-retries.json')
        if target.exists() or target_receipt.exists():raise RuntimeError('Refusing existing archived fixture log')
        source.rename(target)
        if receipt.is_file():receipt.rename(target_receipt)
