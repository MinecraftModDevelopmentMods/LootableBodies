"""Typed NBT and read-only region access for disposable migration fixtures."""
from dataclasses import dataclass
from io import BytesIO
import gzip
import struct
import zlib


@dataclass
class Tag:
    kind: int
    value: object


def _string(stream):
    size = struct.unpack('>H', _read(stream, 2))[0]
    return _read(stream, size).replace(b'\xc0\x80', b'\0').decode('utf-8', 'surrogatepass')


def _string_bytes(text):
    data = text.encode('utf-8', 'surrogatepass').replace(b'\0', b'\xc0\x80')
    if len(data) > 65535:
        raise ValueError('NBT string exceeds Java writeUTF limit')
    return struct.pack('>H', len(data)) + data


def _read(stream, count):
    if count < 0 or count > 64 * 1024 * 1024:
        raise ValueError('Invalid NBT length')
    data = stream.read(count)
    if len(data) != count:
        raise ValueError('Truncated NBT')
    return data


def _payload(stream, kind, depth=0):
    if depth > 512:
        raise ValueError('NBT too deeply nested')
    if kind in (1, 2, 3, 4):
        fmt = {1: '>b', 2: '>h', 3: '>i', 4: '>q'}[kind]
        return Tag(kind, struct.unpack(fmt, _read(stream, struct.calcsize(fmt)))[0])
    if kind in (5, 6):
        # Preserve NaN encodings and signed zero without Python normalization.
        return Tag(kind, _read(stream, 4 if kind == 5 else 8))
    if kind in (7, 11, 12):
        count = struct.unpack('>i', _read(stream, 4))[0]
        return Tag(kind, _read(stream, count * {7: 1, 11: 4, 12: 8}[kind]))
    if kind == 8:
        return Tag(kind, _string(stream))
    if kind == 9:
        element, count = struct.unpack('>bi', _read(stream, 5))
        if count < 0 or count > 1_000_000 or (element == 0 and count):
            raise ValueError('Invalid list length/type')
        return Tag(kind, (element, [_payload(stream, element, depth + 1) for _ in range(count)]))
    if kind == 10:
        compound = {}
        while True:
            child = _read(stream, 1)[0]
            if child == 0:
                return Tag(kind, compound)
            name = _string(stream)
            if name in compound:
                raise ValueError('Duplicate NBT compound key')
            compound[name] = _payload(stream, child, depth + 1)
    raise ValueError('Unknown NBT type ' + str(kind))


def decode(data):
    stream = BytesIO(data)
    if _read(stream, 1) != b'\x0a':
        raise ValueError('Root must be a compound')
    name = _string(stream)
    root = _payload(stream, 10)
    if stream.read(1):
        raise ValueError('Trailing NBT bytes')
    return name, root


def _encode(tag):
    kind, value = tag.kind, tag.value
    if kind in (1, 2, 3, 4):
        return struct.pack({1: '>b', 2: '>h', 3: '>i', 4: '>q'}[kind], value)
    if kind in (5, 6):
        return value
    if kind in (7, 11, 12):
        return struct.pack('>i', len(value) // {7: 1, 11: 4, 12: 8}[kind]) + value
    if kind == 8:
        return _string_bytes(value)
    if kind == 9:
        element, children = value
        if any(child.kind != element for child in children):
            raise ValueError('Mixed NBT list')
        return struct.pack('>bi', element, len(children)) + b''.join(_encode(child) for child in children)
    if kind == 10:
        return b''.join(bytes([child.kind]) + _string_bytes(name) + _encode(child)
                        for name, child in value.items()) + b'\0'
    raise ValueError('Unknown NBT type')


def encode(name, root):
    if root.kind != 10:
        raise ValueError('Root must be compound')
    return b'\x0a' + _string_bytes(name) + _encode(root)


def read_region(path):
    data = path.read_bytes()
    if len(data) < 8192 or len(data) % 4096:
        raise ValueError('Incomplete region')
    chunks = {}
    allocated = {0, 1}
    for index in range(1024):
        location = struct.unpack_from('>I', data, index * 4)[0]
        if not location:
            continue
        offset, sectors = location >> 8, location & 255
        if offset < 2 or not sectors or (offset + sectors) * 4096 > len(data):
            raise ValueError('Invalid region allocation')
        occupied = set(range(offset, offset + sectors))
        if allocated & occupied:
            raise ValueError('Overlapping chunks')
        allocated |= occupied
        length, compression = struct.unpack_from('>IB', data, offset * 4096)
        if length < 2 or length > sectors * 4096 - 4:
            raise ValueError('Invalid compressed chunk length')
        payload = data[offset * 4096 + 5:offset * 4096 + 4 + length]
        raw = gzip.decompress(payload) if compression == 1 else zlib.decompress(payload) if compression == 2 else None
        if raw is None:
            raise ValueError('Unknown compression')
        name, root = decode(raw)
        chunks[index] = {'name': name, 'root': root, 'raw': raw, 'compression': compression,
                         'timestamp': data[4096 + index * 4:4100 + index * 4]}
    return chunks


def write_disposable_region(path, chunks):
    """Caller must validate that path belongs to its new disposable output."""
    header = bytearray(8192)
    body = bytearray()
    for index, chunk in sorted(chunks.items()):
        raw = encode(chunk['name'], chunk['root'])
        payload = gzip.compress(raw, mtime=0) if chunk['compression'] == 1 else zlib.compress(raw)
        sectors = (len(payload) + 5 + 4095) // 4096
        if sectors > 255:
            raise ValueError('Chunk exceeds Anvil sector limit')
        offset = 2 + len(body) // 4096
        struct.pack_into('>I', header, index * 4, (offset << 8) | sectors)
        header[4096 + index * 4:4100 + index * 4] = chunk['timestamp']
        body.extend(struct.pack('>IB', len(payload) + 1, chunk['compression']))
        body.extend(payload)
        body.extend(b'\0' * (sectors * 4096 - len(payload) - 5))
    path.write_bytes(header + body)
