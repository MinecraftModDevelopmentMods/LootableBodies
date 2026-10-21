"""Reject unexpected failures in complete legacy runtime logs."""
import argparse
from pathlib import Path
import re


def audit(path, development=False):
    text=path.read_text(encoding='utf-8',errors='replace')
    failures=[]
    # These exact diagnostics are reproduced by the independent Forge control.
    platform=['FML appears to be missing any signature data. This is not a good thing']
    if development:
        platform+=['The binary patch set is missing. Either you are in a development environment, or things are not going to work!',
                   'Unable to read a class file correctly',
                   'There was a problem reading the entry META-INF/versions/11/net/minecraftforge/launcher/shadow/util/download/DownloadUtilsImpl.class']
    for line in text.splitlines():
        if re.search(r'/ERROR\]|/FATAL\]|\bERROR (?:Error processing|Unable to locate)',line) and not any(message in line for message in platform):
            failures.append(line)
        if re.search(r'AssertionError|NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError|ClassNotFoundException|NullPointerException|OutOfMemoryError|Exception stopping server|Encountered an unexpected exception|Couldn.t render entity|Missing sound for event:|Patcher expecting empty class data|Skipping Entity with id|Unidentified mapping|Unable to load model',line):
            failures.append(line)
        if 'Caused by:' in line and not (development and line.strip()=='Caused by: java.lang.IllegalArgumentException' and
                'META-INF/versions/11/net/minecraftforge/launcher/shadow/util/download/DownloadUtilsImpl.class' in text):
            failures.append(line)
    if not text.strip(): failures.append('Empty runtime log')
    if failures: raise RuntimeError(str(path)+'\n'+'\n'.join(failures))
    return {'path':str(path),'lines':len(text.splitlines()),'expected_platform_errors':sum(
        1 for line in text.splitlines() if re.search(r'/ERROR\]|/FATAL\]',line))}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--development',action='store_true')
    parser.add_argument('logs',nargs='+',type=Path)
    args=parser.parse_args()
    for path in args.logs: print(audit(path,args.development))
