"""Read-only persistence checks for native17 output, not old-writer evidence."""
from nbt_regions import Tag, read_region
import re
import struct

def count_persisted(world,wave):
    if wave not in (-1,0,1,2):raise ValueError('Invalid loaded wave')
    def selected(region):return wave<0 or region.name==['r.0.0.mca','r.8.8.mca','r.16.16.mca'][wave]
    names=('lootablebodies.Corpse','lootablebodies:corpse')
    # Loaded native17 bodies must leave terrain storage, including legacy IDs.
    for region in world.rglob('region/r.*.mca'):
        if not selected(region):continue
        for chunk in read_region(region).values():
            level=chunk['root'].value.get('Level')
            if level is None:raise ValueError('Unexpected target terrain shape')
            for entity in level.value.get('Entities',Tag(9,(10,[]))).value[1]:
                if entity.value.get('id',Tag(8,'')).value in names:
                    raise ValueError('Loaded corpse remained in terrain storage')
    count=0;seen=set()
    for region in world.rglob('entities/r.*.mca'):
        if not selected(region):continue
        coordinates=re.fullmatch(r'r\.(-?[0-9]+)\.(-?[0-9]+)\.mca',region.name)
        if coordinates is None:raise ValueError('Unknown entity region name')
        rx,rz=map(int,coordinates.groups())
        for index,chunk in read_region(region).items():
            root=chunk['root'].value;position=root.get('Position');entities=root.get('Entities')
            if root.get('DataVersion')!=Tag(3,2730):raise ValueError('Wrong native17 entity-chunk data version')
            if position is None or position.kind!=11 or len(position.value)!=8 or struct.unpack('>ii',position.value)!=(rx*32+(index&31),rz*32+(index>>5)):
                raise ValueError('Wrong native entity-chunk Position/index')
            if entities is None or entities.kind!=9 or entities.value[0] not in (0,10):raise ValueError('Wrong native root Entities list')
            for entity in entities.value[1]:
                if entity.value.get('id',Tag(8,'')).value not in names:continue
                if entity.value['id']!=Tag(8,'lootablebodies:corpse') or entity.value.get('LootableBodiesDataVersion')!=Tag(3,7):
                    raise ValueError('Wrong native17 persisted corpse identity/marker')
                identity=entity.value.get('UUID')
                if identity is None or identity.kind!=11 or len(identity.value)!=16:raise ValueError('Wrong native17 corpse UUID type')
                if identity.value in seen:raise ValueError('Duplicate persisted corpse UUID')
                seen.add(identity.value)
                count+=1
    return count
