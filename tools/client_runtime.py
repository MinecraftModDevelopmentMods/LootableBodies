"""Assemble packaged client libraries without ForgeGradle's development bootstrap."""
from pathlib import Path
import hashlib
import shutil


def packaged_library(path):
    name=path.name.lower()
    excluded=('legacydev-','mergetool-','client-extra-','slime-launcher-',
              'asm-6.','asm-util-6.','asm-analysis-6.','asm-tree-6.',
              'slf4j-api-','plexus-utils-','junit','mockito','byte-buddy','opentest4j','apiguardian')
    return path.suffix=='.jar' and not name.startswith(excluded) and '-stable-' not in name


def library_destination(source,home):
    parts=source.parts
    try: index=parts.index('files-2.1')
    except ValueError: raise ValueError('Unknown runtime dependency layout: '+str(source))
    relative=parts[index+1:]
    if len(relative)!=5: raise ValueError('Unknown runtime dependency layout: '+str(source))
    group,artifact,version,checksum,name=relative
    return home/'libraries'/Path(*group.split('.'))/artifact/version/name


def assemble_libraries(dependencies,home):
    result=[]
    for source in dependencies:
        if not packaged_library(source): continue
        target=library_destination(source,home)
        target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(source,target)
        if hashlib.sha256(source.read_bytes()).digest()!=hashlib.sha256(target.read_bytes()).digest():
            raise RuntimeError('Runtime library copy changed: '+str(source))
        result.append(target)
    if not result: raise RuntimeError('Missing packaged client libraries')
    return result
