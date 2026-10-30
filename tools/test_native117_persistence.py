"""Synthetic codec inputs test the inspector; these are not historical worlds."""
from pathlib import Path
import struct,tempfile,unittest
from nbt_regions import Tag,write_disposable_region
from native117_persistence import count_persisted

class Native117PersistenceTest(unittest.TestCase):
    def body(self,number=1,marker=7):
        return Tag(10,{'id':Tag(8,'lootablebodies:corpse'),'LootableBodiesDataVersion':Tag(3,marker),'UUID':Tag(11,struct.pack('>iiii',0,0,0,number))})
    def chunk(self,bodies,version=2730,position=None):
        return {'name':'','root':Tag(10,{'DataVersion':Tag(3,version),'Position':position or Tag(11,struct.pack('>ii',8,8)),
                'Entities':Tag(9,(10,bodies))}),'compression':2,'timestamp':bytes(4)}
    def write(self,world,chunk,dimension=''):
        path=world/dimension/'entities/r.0.0.mca';path.parent.mkdir(parents=True,exist_ok=True);write_disposable_region(path,{264:chunk});return path
    def test_actual_target_shape_counts_all_dimensions_without_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            world=Path(directory);paths=[self.write(world,self.chunk([self.body(i+1)]),dim) for i,dim in enumerate(['','DIM-1','DIM1'])]
            before=[p.read_bytes() for p in paths];self.assertEqual(3,count_persisted(world,0));self.assertEqual(before,[p.read_bytes() for p in paths])
    def test_target_output_rejects_old_marker_and_old_native_version(self):
        for marker,version in [(6,2730),(7,2586)]:
            with self.subTest(marker=marker,version=version),tempfile.TemporaryDirectory() as directory:
                world=Path(directory);self.write(world,self.chunk([self.body(marker=marker)],version=version))
                with self.assertRaises(ValueError):count_persisted(world,0)
    def test_wrong_native_position_type_or_index_is_fatal(self):
        for position in [Tag(9,(3,[Tag(3,8),Tag(3,8)])),Tag(11,struct.pack('>ii',9,8))]:
            with tempfile.TemporaryDirectory() as directory:
                world=Path(directory);self.write(world,self.chunk([self.body()],position=position))
                with self.assertRaises(ValueError):count_persisted(world,0)
    def test_duplicate_persisted_corpse_identity_is_fatal(self):
        with tempfile.TemporaryDirectory() as directory:
            world=Path(directory);self.write(world,self.chunk([self.body(),self.body()]))
            with self.assertRaises(ValueError):count_persisted(world,0)
    def test_loaded_corpse_cannot_be_left_only_in_terrain_storage(self):
        with tempfile.TemporaryDirectory() as directory:
            world=Path(directory);path=world/'region/r.0.0.mca';path.parent.mkdir()
            write_disposable_region(path,{264:{'name':'','root':Tag(10,{'Level':Tag(10,{'Entities':Tag(9,(10,[self.body(marker=6)]))})}),'compression':2,'timestamp':bytes(4)}})
            with self.assertRaises(ValueError):count_persisted(world,0)

if __name__=='__main__':unittest.main()
