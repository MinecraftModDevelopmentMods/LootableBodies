"""Reject unexpected failures in complete legacy runtime logs."""
import argparse
import gzip
from pathlib import Path
import re


FORGE_UPGRADE_RENAME='[fml.ModTracker]: This world was saved with mod Forge which appears to be missing, things may not work well'


def audit(path, development=False, upgrade=False):
    if path.name.endswith('.log.gz'):
        with gzip.open(path,'rt',encoding='utf-8',errors='replace') as stream: text=stream.read()
    else: text=path.read_text(encoding='utf-8',errors='replace')
    failures=[]
    # These exact diagnostics are reproduced by the independent Forge control.
    platform=['FML appears to be missing any signature data. This is not a good thing']
    if development:
        platform+=['The binary patch set is missing. Either you are in a development environment, or things are not going to work!',
                   'There was a problem reading the entry META-INF/versions/11/net/minecraftforge/launcher/shadow/util/download/DownloadUtilsImpl.class']
    launcher_scan=development and bool(re.search(
        r'There was a problem reading the entry [^\r\n]*[/\\]slime-launcher-0\.2\.2\.jar in the jar net\.minecraftforge\.fml\.common\.LoaderException: java\.lang\.IllegalArgumentException - probably a corrupt zip',text))
    # Forge 1.11 ASM 5 scans the launcher's multi-release classes. The same
    # exact diagnostic and stack are retained by forgeControlServer with no mod.
    if launcher_scan: platform.append('Unable to read a class file correctly')
    version_check_block=False
    for line in text.splitlines():
        if line.startswith('['):
            version_check_block=('Forge Version Check/DEBUG]' in line and
                ('[ForgeVersionCheck/forge]: Failed to process update information' in line or
                 (upgrade and '[ForgeVersionCheck/Forge]: Failed to process update information' in line)))
        # The mod-free upgrade control emits both console and DEBUG forms.
        # Match the entire diagnostic so other missing mods/errors still fail.
        known_upgrade_rename = upgrade and any(re.fullmatch(
            r'\[\d{2}:\d{2}:\d{2}\] \[Server thread/ERROR\] ' + re.escape(message), line)
            for message in (FORGE_UPGRADE_RENAME, FORGE_UPGRADE_RENAME.replace(']:', '/]:')))
        known_launcher=launcher_scan and bool(re.search(
            r'There was a problem reading the entry [^\r\n]*[/\\]slime-launcher-0\.2\.2\.jar in the jar net\.minecraftforge\.fml\.common\.LoaderException: java\.lang\.IllegalArgumentException - probably a corrupt zip',line))
        if re.search(r'/ERROR\]|/FATAL\]|\bERROR (?:Error processing|Unable to locate)',line) and not known_launcher and not known_upgrade_rename and not any(message in line for message in platform):
            failures.append(line)
        if re.search(r'AssertionError|NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError|ClassNotFoundException|NullPointerException|OutOfMemoryError|Exception stopping server|Encountered an unexpected exception|Couldn.t render entity|Missing sound for event:|Patcher expecting empty class data|Skipping Entity with id|Unidentified mapping|Unable to load model',line):
            failures.append(line)
        # Reproduced in the Forge-only control's complete DEBUG log: its old
        # HTTP promotions URL returns "Moved Permanently" instead of JSON.
        stock_version_redirect=(version_check_block and 'Moved Permanently' in text and
            ('net.minecraftforge.common.ForgeVersion$1.process(ForgeVersion.java:227)' in text or
             (upgrade and 'net.minecraftforge.common.ForgeVersion$1.process(ForgeVersion.java:216)' in text)) and
            line.strip()=='Caused by: java.lang.IllegalStateException: Expected BEGIN_OBJECT but was STRING at line 1 column 1')
        if 'Caused by:' in line and not stock_version_redirect and not (development and line.strip()=='Caused by: java.lang.IllegalArgumentException' and
                ('META-INF/versions/11/net/minecraftforge/launcher/shadow/util/download/DownloadUtilsImpl.class' in text or launcher_scan)):
            failures.append(line)
    if not text.strip(): failures.append('Empty runtime log')
    if failures: raise RuntimeError(str(path)+'\n'+'\n'.join(failures))
    return {'path':str(path),'lines':len(text.splitlines()),'expected_platform_errors':sum(
        1 for line in text.splitlines() if re.search(r'/ERROR\]|/FATAL\]',line))}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--development',action='store_true')
    parser.add_argument('--upgrade',action='store_true')
    parser.add_argument('--recursive',action='store_true')
    parser.add_argument('logs',nargs='+',type=Path)
    args=parser.parse_args()
    for path in args.logs:
        paths=[path]
        if args.recursive and path.is_dir():
            paths=sorted(p for p in path.rglob('*') if p.is_file() and (p.name.endswith('.log') or p.name.endswith('.log.gz')))
            if not paths: raise RuntimeError('Missing runtime logs: '+str(path))
            if list(path.rglob('crash-reports/*.txt')): raise RuntimeError('Unexpected crash reports: '+str(path))
        for log in paths: print(audit(log,args.development,args.upgrade))
