"""Read-only native 1.20.6 saved entity inspection, distinct from old-writer proof."""
import re,struct
from nbt_regions import Tag,read_region

def count_persisted(world,wave):
    if wave not in (-1,0,1,2):raise ValueError('Invalid loaded wave')
    def selected(region):return wave<0 or region.name==['r.0.0.mca','r.8.8.mca','r.16.16.mca'][wave]
    names=('lootablebodies.Corpse','lootablebodies:corpse')
    # A region can still contain unopened old terrain chunks. Inspect both
    # actual layouts without relabelling untouched terrain as converted20.
    for region in world.rglob('region/r.*.mca'):
        if not selected(region):continue
        for chunk in read_region(region).values():
            root=chunk['root'].value
            level=root.get('Level')
            entries=level.value.get('Entities') if level is not None and level.kind==10 else root.get('entities')
            if entries is None:continue
            if entries.kind!=9 or entries.value[0] not in (0,10):raise ValueError('Malformed terrain entity list')
            if any(e.value.get('id',Tag(8,'')).value in names for e in entries.value[1]):
                raise ValueError('Loaded corpse remained in terrain storage')
    count=0;seen=set()
    for region in world.rglob('entities/r.*.mca'):
        if not selected(region):continue
        match=re.fullmatch(r'r\.(-?[0-9]+)\.(-?[0-9]+)\.mca',region.name)
        if match is None:raise ValueError('Unknown entity region name')
        rx,rz=map(int,match.groups())
        for index,chunk in read_region(region).items():
            root=chunk['root'].value;position=root.get('Position');entities=root.get('Entities')
            if root.get('DataVersion')!=Tag(3,3839):raise ValueError('Wrong native 1.20.6 entity-chunk data version')
            if position is None or position.kind!=11 or len(position.value)!=8 or struct.unpack('>ii',position.value)!=(rx*32+(index&31),rz*32+(index>>5)):
                raise ValueError('Wrong native 1.20.6 entity-chunk Position/index')
            if entities is None or entities.kind!=9 or entities.value[0] not in (0,10):raise ValueError('Wrong native 1.20.6 root Entities list')
            for entity in entities.value[1]:
                if entity.value.get('id',Tag(8,'')).value not in names:continue
                if entity.value['id']!=Tag(8,'lootablebodies:corpse') or entity.value.get('LootableBodiesDataVersion')!=Tag(3,11):raise ValueError('Wrong native 1.20.6 corpse identity/marker')
                identity=entity.value.get('UUID')
                if identity is None or identity.kind!=11 or len(identity.value)!=16:raise ValueError('Wrong native 1.20.6 corpse UUID type')
                if identity.value in seen:raise ValueError('Duplicate persisted corpse UUID')
                seen.add(identity.value);count+=1
    return count
