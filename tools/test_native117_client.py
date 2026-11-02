import json
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import native118_client
import native_runtime


class Native117ClientTests(unittest.TestCase):
    def test_native_module_metadata_is_used_instead_of_legacy_classpath_only_launch(self):
        with patch('native118_client.command',return_value=['native40-command']) as launch:
            result=native_runtime.client_command(Path('java'),Path('home'),Path('sdk'),Path('assets'),prepared=Path('prepared'),username='Player',extra=['-Dprobe=true'])
        self.assertEqual(['native40-command'],result)
        self.assertEqual(Path('prepared'),launch.call_args.args[4])
        self.assertEqual('Player',launch.call_args.kwargs['username'])

    def test_feature_os_and_version_rules_do_not_enable_demo_or_wrong_platform_flags(self):
        arguments=[{'rules':[{'action':'allow','features':{'is_demo_user':True}}],'value':'--demo'},
                   {'rules':[{'action':'allow','features':{'has_custom_resolution':True}}],'value':['--width','${width}']},
                   {'rules':[{'action':'allow','os':{'name':'windows','version':'^10\\.'}}],'value':'-Dos.version=10.0'},
                   {'rules':[{'action':'allow','os':{'name':'osx'}}],'value':'-XstartOnFirstThread'}]
        self.assertEqual(['--width','854','-Dos.version=10.0'],native118_client.expand(arguments,{'width':'854'},system='windows',architecture='amd64',os_version='10.0.26200'))

    def test_unknown_placeholder_fails_before_a_process_can_be_started(self):
        with self.assertRaises(KeyError):
            native118_client.expand(['${unrecognised}'],{},system='windows',architecture='amd64',os_version='10.0')

    def test_jvm_filters_the_actual_base_jar_while_game_keeps_its_forge_profile_version(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);java=root/'jdk/bin/java.exe';java.parent.mkdir(parents=True)
            (java.parent.parent/'release').write_text('SEMANTIC_VERSION="17.0.20.1+1"\nIMPLEMENTOR="Eclipse Adoptium"\n')
            index=root/'assets/indexes/1.18.json';index.parent.mkdir(parents=True);index.write_bytes(b'{}')
            base={'assetIndex':{'id':'1.18','sha1':hashlib.sha1(b'{}').hexdigest()},'arguments':{'jvm':['-cp','${classpath}'],'game':['--version','${version_name}','--accessToken','${auth_access_token}','--clientId','${clientid}','--xuid','${auth_xuid}']}}
            forge={'arguments':{'jvm':['-DignoreList=forge-,${version_name}.jar'],'game':['--launchTarget','forgeclient']},'mainClass':'cpw.mods.bootstraplauncher.BootstrapLauncher'}
            record={'library_directory':str(root/'sdk/libraries'),'classpath':[str(root/'1.18.2.jar')],'natives':str(root/'natives'),'system':'windows','architecture':'amd64'}
            with patch('native118_client.load',return_value=(base,forge,record)):
                result=native118_client.command(java,root/'home',root/'sdk',root/'assets',root/'prepared',username='Fixture')
            self.assertIn('-DignoreList=forge-,1.18.2.jar',result)
            self.assertEqual('1.18.2-forge-40.3.12',result[result.index('--version')+1])
            self.assertEqual('0',result[result.index('--accessToken')+1])
            self.assertEqual('',result[result.index('--clientId')+1]);self.assertEqual('',result[result.index('--xuid')+1])

    def test_unsealed_classpath_and_changed_native_bytes_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);library=root/'native.dll';library.write_bytes(b'original')
            record={'version':native118_client.VERSION,'sdk':str(root.resolve()),'system':'windows','architecture':'amd64',
                    'files':{str(library):native_runtime.digest(library)},'classpath':[str(root/'unsealed.jar')]}
            (root/'inputs.json').write_text(json.dumps(record))
            with patch('native118_client.descriptors',return_value=({},{})),patch('native118_client.platform.system',return_value='Windows'),patch('native118_client.platform.architecture',return_value=('64bit','')):
                with self.assertRaisesRegex(RuntimeError,'Unsealed'):
                    native118_client.load(root,root)
                library.write_bytes(b'changed')
                with self.assertRaisesRegex(RuntimeError,'Changed prepared'):
                    native118_client.load(root,root)


if __name__=='__main__':unittest.main()
