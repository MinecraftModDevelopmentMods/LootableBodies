"""Reject unexpected failures in complete legacy runtime logs."""
import argparse
import gzip
from pathlib import Path
import re


FORGE_UPGRADE_RENAME='[FML.ModTracker]: This world was saved with mod Forge which appears to be missing, things may not work well'


def development_control_lines(lines):
    """Accept only consecutive bootstrap blocks reproduced without this mod."""
    accepted=set()
    header=re.compile(r'^\[\d{2}:\d{2}:\d{2}\] \[[^]]+\] \[FML/?\]: (.*)$')
    boundaries=[i for i,line in enumerate(lines) if line.startswith('[')]+[len(lines)]
    blocks=[(a,b,header.fullmatch(lines[a])) for a,b in zip(boundaries,boundaries[1:])]
    def stack(a,b):
        body=lines[a+1:b]
        return any('discovery.asm.ASMModParser.<init>' in line for line in body) and all(
            not line.strip() or line in ('java.lang.IllegalArgumentException: null',
            'net.minecraftforge.fml.common.LoaderException: java.lang.IllegalArgumentException',
            'Caused by: java.lang.IllegalArgumentException') or
            re.fullmatch(r'\s+at (?:org\.objectweb\.asm\.|net\.minecraftforge\.fml\.|net\.minecraft\.|java\.|sun\.|net\.minecraftforge\.launcher\.|net\.minecraftforge\.gradle\.)[^\r\n]+',line) or
            re.fullmatch(r'\s+\.\.\. \d+ more',line) for line in body)
    scan=re.compile(r'There was a problem reading the entry (?:'
        r'META-INF/versions/11/net/minecraftforge/launcher/shadow/util/download/DownloadUtilsImpl\.class in the jar .*[\\/]slime-launcher-0\.2\.2\.jar|'
        r'module-info\.class in the jar .*[\\/]asm(?:-util|-analysis|-tree)?-6\.2\.jar|'
        r'META-INF/versions/9/module-info\.class in the jar .*[\\/]slf4j-api-1\.8\.0-beta4\.jar) - probably a corrupt zip')
    for i,(a,b,match) in enumerate(blocks):
        if not match: continue
        message=match.group(1)
        if message=='Apache Maven library folder was not in the format expected. Using default libraries directory.' and i+2<len(blocks):
            full,trim=blocks[i+1],blocks[i+2]
            if full[2] and trim[2] and re.fullmatch(r'Full: .*maven-artifact-3\.5\.3\.jar',full[2].group(1)) and re.fullmatch(r'Trimmed: .*maven-artifact/3\.5\.3/',trim[2].group(1)):
                accepted.update((a,full[0],trim[0]))
        if scan.fullmatch(message) and i>0 and i+1<len(blocks):
            previous,following=blocks[i-1],blocks[i+1]
            jar=message.split(' in the jar ',1)[1].rsplit(' - probably',1)[0].replace('\\','/').rsplit('/',1)[-1]
            if previous[2] and previous[2].group(1)=='Unable to read a class file correctly' and following[2] and following[2].group(1)=='Zip file '+jar+' failed to read properly, it will be ignored' and all(stack(x,y) for x,y,_ in (previous,(a,b,match),following)):
                accepted.update(range(previous[0],following[1]))
    return accepted


def native_control_line(line):
    """Exact Forge 25 diagnostics reproduced by the independent platform control."""
    if line == 'AL lib: (EE) alc_cleanup: 1 device not closed':
        return True
    header=r'\[(?:\d{2}[A-Za-z]{3}\d{4} )?\d{2}:\d{2}:\d{2}(?:\.\d{3})?\] \[[^]\r\n]+/FATAL\] '
    return bool(re.fullmatch(header+r'(?:'
        r'\[(?:net\.minecraftforge\.fml\.packs\.ModFileResourcePack|ne\.mi\.fm\.pa\.ModFileResourcePack)/?\]: Failed to clean up tempdir [^\r\n]+[\\/]modpacktmp\d+|'
        r'\[(?:net\.minecraftforge\.common\.ForgeConfig|ne\.mi\.co\.ForgeConfig)(?:/CORE|/)?\]: Forge config just got changed on the file system!)',line))


def audit(path, development=False, upgrade=False, fixture_logins=False, native=False):
    if path.name.endswith('.log.gz'):
        with gzip.open(path,'rt',encoding='utf-8',errors='replace') as stream: text=stream.read()
    else: text=path.read_text(encoding='utf-8',errors='replace')
    failures=[]
    from fixture_login import receipt_lines
    login_lines=receipt_lines(path,text) if fixture_logins else set()
    # These exact diagnostics are reproduced by the independent Forge control.
    platform=['FML appears to be missing any signature data. This is not a good thing']
    if development:
        platform.append('The binary patch set is missing. Either you are in a development environment, or things are not going to work!')
    lines=text.splitlines()
    proven=development_control_lines(lines) if development else set()
    version_check_block=False
    for number,line in enumerate(lines):
        if number in login_lines: continue
        if line.startswith('['):
            version_check_block=('Forge Version Check/DEBUG]' in line and
                ('[ForgeVersionCheck/forge]: Failed to process update information' in line or
                 (upgrade and '[ForgeVersionCheck/Forge]: Failed to process update information' in line)))
        # The mod-free upgrade control emits both console and DEBUG forms.
        # Match the entire diagnostic so other missing mods/errors still fail.
        known_upgrade_rename = upgrade and any(re.fullmatch(
            r'\[\d{2}:\d{2}:\d{2}\] \[Server thread/ERROR\] ' + re.escape(message), line)
            for message in (FORGE_UPGRADE_RENAME, FORGE_UPGRADE_RENAME.replace(']:', '/]:'), FORGE_UPGRADE_RENAME.replace('FML.ModTracker','fml.ModTracker'), FORGE_UPGRADE_RENAME.replace('FML.ModTracker','fml.ModTracker').replace(']:','/]:')))
        if re.search(r'/ERROR\]|/FATAL\]|\bERROR (?:Error processing|Unable to locate)|^AL lib: \(EE\)',line) and number not in proven and not known_upgrade_rename and not (native and native_control_line(line)) and not any(message in line for message in platform):
            failures.append(line)
        if re.search(r'Can.t serialize unregistered packet|AssertionError|NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError|ClassNotFoundException|NullPointerException|OutOfMemoryError|Exception stopping server|Encountered an unexpected exception|Couldn.t render entity|Missing sound for event:|Patcher expecting empty class data|Skipping Entity with id|Unidentified mapping|Unable to load model',line):
            failures.append(line)
        # Reproduced in the Forge-only control's complete DEBUG log: its old
        # HTTP promotions URL returns "Moved Permanently" instead of JSON.
        stock_version_redirect=(version_check_block and 'Moved Permanently' in text and
            ('net.minecraftforge.common.ForgeVersion$1.process(ForgeVersion.java:227)' in text or
             (upgrade and 'net.minecraftforge.common.ForgeVersion$1.process(ForgeVersion.java:216)' in text)) and
            line.strip()=='Caused by: java.lang.IllegalStateException: Expected BEGIN_OBJECT but was STRING at line 1 column 1')
        if 'Caused by:' in line and not stock_version_redirect and number not in proven:
            failures.append(line)
    if not text.strip(): failures.append('Empty runtime log')
    if failures: raise RuntimeError(str(path)+'\n'+'\n'.join(failures))
    return {'path':str(path),'lines':len(text.splitlines()),'recovered_forge_pre_login_failures':len(login_lines),'expected_platform_errors':sum(
        1 for line in text.splitlines() if re.search(r'/ERROR\]|/FATAL\]',line))}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--native',action='store_true',help='Allow only independently reproduced Forge 25 platform diagnostics')
    parser.add_argument('--development',action='store_true')
    parser.add_argument('--upgrade',action='store_true')
    parser.add_argument('--recursive',action='store_true')
    parser.add_argument('--fixture-logins',action='store_true',help='Require hashed receipts for recovered Forge-only pre-login encoding failures')
    parser.add_argument('logs',nargs='+',type=Path)
    args=parser.parse_args()
    for path in args.logs:
        paths=[path]
        if args.recursive and path.is_dir():
            paths=sorted(p for p in path.rglob('*') if p.is_file() and (p.name.endswith('.log') or p.name.endswith('.log.gz')))
            if not paths: raise RuntimeError('Missing runtime logs: '+str(path))
            if list(path.rglob('crash-reports/*.txt')): raise RuntimeError('Unexpected crash reports: '+str(path))
        for log in paths: print(audit(log,args.development,args.upgrade,args.fixture_logins,args.native))
