"""Verify generated native SDK archives despite installer container metadata."""
import hashlib,json,struct,zipfile
from pathlib import PurePosixPath

def timestamp_extra(extra):
    # Java's directory-backed installer emits NTFS times on Windows and
    # extended Unix times on Linux. No other extra field is admitted here.
    if len(extra)<4:raise ValueError('Missing installer timestamp metadata')
    kind,size=struct.unpack('<HH',extra[:4]);data=extra[4:]
    if size!=len(data):raise ValueError('Truncated installer timestamp metadata')
    if kind==0x000a:
        if len(data)!=32 or data[:8]!=b'\0\0\0\0\1\0\x18\0':raise ValueError('Invalid NTFS timestamp metadata')
    elif kind==0x5455:
        if len(data)!=5 or data[0]!=7:raise ValueError('Invalid Unix timestamp metadata')
    else:raise ValueError('Unknown generated SDK extra field')

def archive_seal(path, *, timestamp_policy):
    if timestamp_policy not in ('exact','installer-times'):raise ValueError('Unknown SDK archive policy')
    with zipfile.ZipFile(path) as archive:
        entries=archive.infolist();names=[i.filename for i in entries]
        if not names or len(names)!=len(set(names)):raise ValueError('Missing or duplicate SDK entries')
        result=[]
        for entry in sorted(entries,key=lambda e:e.filename):
            name=PurePosixPath(entry.filename)
            if name.is_absolute() or '..' in name.parts or '\\' in entry.filename or ':' in entry.filename:raise ValueError('Unsafe SDK archive entry')
            if timestamp_policy=='installer-times':timestamp_extra(entry.extra)
            metadata={key:getattr(entry,key) for key in ('compress_type','create_system','create_version','extract_version','flag_bits','volume','internal_attr','external_attr','CRC','compress_size','file_size')}
            metadata.update(name=entry.filename,comment=entry.comment.hex(),content_sha256=hashlib.sha256(archive.read(entry)).hexdigest().upper(),date_time=None if timestamp_policy=='installer-times' and entry.is_dir() else entry.date_time,extra='installer-times' if timestamp_policy=='installer-times' else entry.extra.hex())
            result.append(metadata)
        value={'comment':archive.comment.hex(),'entries':result}
    return {'entries':len(entries),'content_and_metadata_sha256':hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':')).encode()).hexdigest().upper()}

def verify_generated_archive(path, expected):
    actual=archive_seal(path,timestamp_policy=expected['timestamp_policy'])
    if actual!={key:expected[key] for key in ('entries','content_and_metadata_sha256')}:
        raise ValueError('Changed generated native SDK class, resource or invariant metadata: '+str(path))
    return actual
