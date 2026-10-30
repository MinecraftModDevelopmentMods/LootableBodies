"""Exact packaged recovery of raw malformed fixtures, with deferred chunks and registry return."""
import argparse,json,shutil
from pathlib import Path
from verify_packaged import MAIN,prepare,run,digest,fingerprint
from native_runtime import seed_flat_fixture

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--java',type=Path,required=True);p.add_argument('--forge-home',type=Path,required=True)
    p.add_argument('--flat-header',type=Path,required=True);p.add_argument('--flat-header-sha256',required=True)
    p.add_argument('--baseline-jar',type=Path);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    project=Path(__file__).resolve().parents[1];root=a.output.resolve();root.mkdir(parents=True,exist_ok=False)
    java=a.java.resolve();distribution=a.forge_home.resolve();main=project/'build/libs'/MAIN;baseline=a.baseline_jar.resolve() if a.baseline_jar else main;probe=project/'build/integration-test-mod/lootablebodies-build-tests-reobf.jar'
    original=digest(baseline);candidate=digest(main)
    seed=root/'sealed-seed';prepare(seed,distribution,baseline,probe)
    seed_flat_fixture(seed,a.flat_header,a.flat_header_sha256)
    run(java,seed,'nbt-seed');sealed=fingerprint(seed/'world')
    home=root/'candidate';prepare(home,distribution,main,probe);shutil.copytree(seed/'world',home/'world')
    far=home/'world/region/r.8.8.mca';deferred=digest(far)
    run(java,home,'nbt-reload');assert digest(far)==deferred,'Unopened malformed region changed'
    for phase in ('nbt-deferred','nbt-deferred-repeat','nbt-return','nbt-return-reload'):run(java,home,phase)
    expected=['nbt-reload','nbt-deferred','nbt-deferred-repeat','nbt-return','nbt-return-reload']
    assert (home/'world/nbt-world-checks.txt').read_text().splitlines()==['PASS '+s+' exact_items_raw_records_identity' for s in expected]
    assert fingerprint(seed/'world')==sealed and digest(baseline)==original and digest(main)==candidate,'A sealed source/candidate changed'
    report={'candidate_sha256':candidate,'baseline_sha256':original,'sealed_world_unchanged':True,'unopened_malformed_region_unchanged':deferred,'checks':expected,'raw_records_per_corpse_before_return':5,'raw_records_per_corpse_after_return':4,'raw_scalar_records_per_corpse':4,'raw_health_attribute_records_per_corpse':5,'raw_effect_hurt_records_per_corpse':3,'raw_geometry_records_per_corpse':2,'raw_rotation_fall_records_per_corpse':2,'raw_metadata_records_per_corpse':16,'source_world':sealed}
    (root/'result.json').write_text(json.dumps(report,indent=2)+'\n');print('PACKAGED_NBT_RECOVERY_PASS',flush=True)

if __name__=='__main__':main()
