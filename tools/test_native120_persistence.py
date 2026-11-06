"""Synthetic native20 codec shapes; no historical JVM qualification is claimed."""
from pathlib import Path
import gzip,struct,tempfile,unittest
from nbt_regions import Tag,write_disposable_region,encode,read_region
from native120_persistence import count_persisted
from verify_world_upgrades import patch_stamps

class Native120PersistenceTest(unittest.TestCase):
    def body(self,number=1,marker=10):return Tag(10,{'id':Tag(8,'lootablebodies:corpse'),'LootableBodiesDataVersion':Tag(3,marker),'UUID':Tag(11,struct.pack('>iiii',0,0,0,number))})
    def chunk(self,bodies,version=3465,position=(8,8)):
        return {'name':'','root':Tag(10,{'DataVersion':Tag(3,version),'Position':Tag(11,struct.pack('>ii',*position)),'Entities':Tag(9,(10,bodies))}),'compression':2,'timestamp':bytes(4)}
    def write(self,world,chunk,dimension=''):
        path=world/dimension/'entities/r.0.0.mca';path.parent.mkdir(parents=True,exist_ok=True);write_disposable_region(path,{264:chunk});return path
    def test_native20_saved_entities_count_all_dimensions_without_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            world=Path(directory);paths=[self.write(world,self.chunk([self.body(i+1)]),dim) for i,dim in enumerate(['','DIM-1','DIM1'])]
            before=[p.read_bytes() for p in paths];self.assertEqual(3,count_persisted(world,0));self.assertEqual(before,[p.read_bytes() for p in paths])
    def test_previous_marker_native_version_or_wrong_entity_chunk_position_is_fatal(self):
        for marker,version,position in [(9,3465,(8,8)),(10,3337,(8,8)),(10,3465,(9,8))]:
            with self.subTest(marker=marker,version=version,position=position),tempfile.TemporaryDirectory() as directory:
                world=Path(directory);self.write(world,self.chunk([self.body(marker=marker)],version,position))
                with self.assertRaises(ValueError):count_persisted(world,0)
    def test_duplicate_active_id_and_malformed_list_cannot_pass(self):
        for duplicate in [True,False]:
            with self.subTest(duplicate=duplicate),tempfile.TemporaryDirectory() as directory:
                world=Path(directory);chunk=self.chunk([self.body(),self.body()] if duplicate else [self.body()])
                if not duplicate:chunk['root'].value['Entities']=Tag(8,'malformed')
                self.write(world,chunk)
                with self.assertRaises(ValueError):count_persisted(world,0)
    def test_corpses_in_old_or_flattened_terrain_cannot_count_as_saved_native20(self):
        for flattened in [False,True]:
            with self.subTest(flattened=flattened),tempfile.TemporaryDirectory() as directory:
                world=Path(directory);root={'entities':Tag(9,(10,[self.body()]))} if flattened else {'Level':Tag(10,{'Entities':Tag(9,(10,[self.body(1,7)]))})}
                p=world/'region/r.0.0.mca';p.parent.mkdir();write_disposable_region(p,{264:{'name':'','root':Tag(10,root),'compression':2,'timestamp':bytes(4)}})
                with self.assertRaises(ValueError):count_persisted(world,0)
    def test_stamp_damage_exercises_all_nine_native17_entity_chunks(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);world=root/'owned/world';world.mkdir(parents=True)
            (world/'level.dat').write_bytes(gzip.compress(encode('',Tag(10,{'Data':Tag(10,{'DataVersion':Tag(3,2730)})})),mtime=0))
            paths=[]
            for dimension,dim in enumerate(['','DIM-1','DIM1']):
                for wave,coordinate in enumerate([8,256,512]):
                    path=world/dim/'entities'/('r.%d.%d.mca'%(coordinate//32,coordinate//32));path.parent.mkdir(parents=True,exist_ok=True)
                    index=(coordinate&31)+(coordinate&31)*32
                    write_disposable_region(path,{index:self.chunk([self.body(wave*3+dimension+1,7)],2730,(coordinate,coordinate))});paths.append(path)
            changes=patch_stamps(world,root)
            self.assertEqual(list(range(9)),sorted(c['stamp_case'] for c in changes))
            self.assertEqual(4,next(c for c in changes if c['stamp_case']==6)['data_version_type'])
            for path in paths:
                for chunk in read_region(path).values():self.assertEqual(7,chunk['root'].value['Entities'].value[1][0].value['LootableBodiesDataVersion'].value)
    def test_valid_future_entity_fixture_preserves_original_and_rejects_wrong_source(self):
        from verify_upgrade_abort import inject_future_entity_version
        for version in [2975,3337]:
            with self.subTest(version=version),tempfile.TemporaryDirectory() as directory:
                home=Path(directory);world=home/'world';path=self.write(world,self.chunk([self.body(marker=8)],version))
                original=path.read_bytes()
                if version!=2975:
                    with self.assertRaises(ValueError):inject_future_entity_version(world)
                    self.assertEqual(original,path.read_bytes());self.assertFalse((home/'future-entity-original.mca').exists())
                else:
                    mutation=inject_future_entity_version(world)
                    self.assertEqual(original,(home/'future-entity-original.mca').read_bytes())
                    self.assertEqual(Tag(3,3466),read_region(path)[264]['root'].value['DataVersion'])
                    self.assertEqual(8,mutation['corpse_marker'])

if __name__=='__main__':unittest.main()
