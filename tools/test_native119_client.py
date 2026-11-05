import hashlib,json,shutil,struct,tempfile,unittest,zipfile
from pathlib import Path
from unittest.mock import patch
import native119_client as client


class Native119ArchitectureTests(unittest.TestCase):
    def test_os_only_rules_cannot_select_other_windows_architectures(self):
        libraries=json.loads((Path(__file__).parent/'fixtures/native119-native-library-excerpt.json').read_text())['libraries']
        for architecture,expected in [('amd64','natives-windows'),('x86','natives-windows-x86'),('arm64','natives-windows-arm64')]:
            self.assertEqual([expected],[item['name'].split(':')[3] for item in libraries if client.native_for_platform(item,'windows',architecture)])
        with self.assertRaises(RuntimeError):client.native_for_platform(libraries[0],'windows','unknown')

    def test_native_dll_header_and_actual_cpu_type_are_required(self):
        image=bytearray(128);image[:2]=b'MZ';struct.pack_into('<I',image,0x3c,64);image[64:68]=b'PE\0\0';struct.pack_into('<H',image,68,0x8664)
        client.validate_native_image(image,'windows','amd64')
        for content in (b'',b'MZ',bytes(128),image[:65]):
            with self.subTest(content=bytes(content[:12])),self.assertRaises(RuntimeError):client.validate_native_image(content,'windows','amd64')
        for machine in (0x014c,0xAA64):
            struct.pack_into('<H',image,68,machine)
            with self.subTest(machine=machine),self.assertRaises(RuntimeError):client.validate_native_image(image,'windows','amd64')

    def test_current_native_artifacts_select_and_extract_only_the_actual_platform(self):
        root=Path(tempfile.mkdtemp(prefix='lootable-client-native-unit-'))
        try:
            metadata=json.loads((Path(__file__).parent/'fixtures/native119-native-library-excerpt.json').read_text())
            libraries=metadata['libraries']
            self.assertEqual(3,len(libraries))
            for library in libraries:
                self.assertEqual([{'action':'allow','os':{'name':'windows'}}],library['rules'])
                record=library['downloads']['artifact'];path=root/'sdk/libraries'/record['path'];path.parent.mkdir(parents=True,exist_ok=True)
                machine=0xAA64 if library['name'].endswith('arm64') else 0x014c if library['name'].endswith('x86') else 0x8664
                image=bytearray(128);image[:2]=b'MZ';struct.pack_into('<I',image,0x3c,64);image[64:68]=b'PE\0\0';struct.pack_into('<H',image,68,machine)
                with zipfile.ZipFile(path,'w') as archive:archive.writestr('glfw.dll',image)
                # Retain the real descriptor names/rules/layout; this is an
                # explicitly synthetic, hash-bound PE archive for the unit seam.
                record['sha1']=hashlib.sha1(path.read_bytes()).hexdigest()
            base={'libraries':libraries,'downloads':{'client':{'sha1':hashlib.sha1(b'unit-client').hexdigest()}}}
            forge={'libraries':[]}
            version=root/'sdk/versions/1.19.4';version.mkdir(parents=True);(version/'1.19.4.jar').write_bytes(b'unit-client');(version/'1.19.4.json').write_text('{}')
            profile=root/'sdk/versions/1.19.4-forge-45.4.5';profile.mkdir();(profile/'1.19.4-forge-45.4.5.json').write_text('{}')
            with patch.object(client,'descriptors',return_value=(base,forge)),patch.object(client,'BASE_VERSION','1.19.4'),patch.object(client,'VERSION','1.19.4-forge-45.4.5'),patch.object(client.platform,'system',return_value='Windows'),patch.object(client.platform,'architecture',return_value=('64bit','')):
                prepared=client.prepare(root/'sdk',root/'prepared')
            print('NATIVE_CLIENT_UNIT_FIXTURE='+str(root))
            native=Path(prepared['natives'])/'glfw.dll'
            self.assertTrue(native.is_file(),'Current descriptor native artifacts were not extracted')
            content=native.read_bytes();offset=struct.unpack_from('<I',content,0x3c)[0]
            self.assertEqual(0x8664,struct.unpack_from('<H',content,offset+4)[0])
            selected=[Path(p).name for p in prepared['classpath'] if 'natives-windows' in p]
            self.assertEqual(['lwjgl-glfw-3.3.1-natives-windows.jar'],selected)
        except BaseException:
            print('RETAINED_NATIVE_CLIENT_UNIT_FIXTURE='+str(root));raise
        else:shutil.rmtree(root)


if __name__=='__main__':unittest.main()
