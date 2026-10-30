"""Validate sealed, already-written historical worlds without regenerating them."""
import json,re
from pathlib import Path
from native_runtime import digest,fingerprint


def load(path,expected,requested):
    path=Path(path).resolve()
    def unique(pairs):
        result={}
        for name,value in pairs:
            if name in result:raise ValueError('Duplicate prepared-manifest key: '+name)
            result[name]=value
        return result
    record=json.loads(path.read_text(encoding='utf-8'),object_pairs_hook=unique)
    if record.get('schema')!=1 or record.get('target_data_version')!=2730:
        raise ValueError('Wrong native17 prepared manifest schema/target')
    origins=record.get('origins',{})
    if set(origins)!=set(expected):raise ValueError('Prepared manifest must contain every exact origin')
    paths=[Path(details['world']).resolve() for details in origins.values()]
    if len(set(paths))!=len(paths):raise ValueError('Prepared origins must identify distinct source worlds')
    if not requested or len(set(requested))!=len(requested) or not set(requested)<=set(expected):
        raise ValueError('Invalid prepared origin selection')
    result={}
    for name in requested:
        details=origins[name];world=Path(details['world'])
        minecraft,version,jar=expected[name]
        if details.get('jar_sha256')!=jar or details.get('minecraft','').split(' via ')[0]!=minecraft or details.get('mod_version','').split(' via ')[0]!=version:
            raise ValueError('Historical source identity mismatch: '+name)
        files=details.get('files',{})
        if not files or not all(isinstance(n,str) and not Path(n).is_absolute() and '..' not in Path(n).parts and re.fullmatch('[A-F0-9]{64}',sha or '') for n,sha in files.items()):
            raise ValueError('Missing or invalid prepared file seal: '+name)
        if not world.is_absolute() or not world.is_dir() or world.is_symlink() or any(p.is_symlink() for p in world.rglob('*')):
            raise ValueError('Prepared world must be an absolute ordinary directory: '+name)
        if fingerprint(world)!=files:raise ValueError('Sealed prepared source changed: '+name)
        result[name]=(world.resolve(),dict(details,world=files))
    return result,{'path':str(path),'sha256':digest(path),'schema':1,'all_origins':len(origins)}
