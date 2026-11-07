import json
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import native120_client
import native_runtime


class Native120ClientContractTests(unittest.TestCase):
    def test_native_module_metadata_is_used_instead_of_legacy_classpath_only_launch(self):
        with patch('native120_client.command',return_value=['native47-command']) as launch:
            result=native_runtime.client_command(Path('java'),Path('home'),Path('sdk'),Path('assets'),prepared=Path('prepared'),username='Player',extra=['-Dprobe=true'])
        self.assertEqual(['native47-command'],result)
        self.assertEqual(Path('prepared'),launch.call_args.args[4])
        self.assertEqual('Player',launch.call_args.kwargs['username'])

    def test_feature_os_and_version_rules_do_not_enable_demo_or_wrong_platform_flags(self):
        arguments=[{'rules':[{'action':'allow','features':{'is_demo_user':True}}],'value':'--demo'},
                   {'rules':[{'action':'allow','features':{'has_custom_resolution':True}}],'value':['--width','${width}']},
                   {'rules':[{'action':'allow','os':{'name':'windows','version':'^10\\.'}}],'value':'-Dos.version=10.0'},
                   {'rules':[{'action':'allow','os':{'name':'osx'}}],'value':'-XstartOnFirstThread'}]
        self.assertEqual(['--width','854','-Dos.version=10.0'],native120_client.expand(arguments,{'width':'854'},system='windows',architecture='amd64',os_version='10.0.26200'))

    def test_unknown_placeholder_fails_before_a_process_can_be_started(self):
        with self.assertRaises(KeyError):
            native120_client.expand(['${unrecognised}'],{},system='windows',architecture='amd64',os_version='10.0')

    def test_forge_bootstrap_uses_installed_client_descriptor_and_exact_java21(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);java=root/'jdk/bin/java.exe';java.parent.mkdir(parents=True)
            (java.parent.parent/'release').write_text('JAVA_RUNTIME_VERSION="21.0.7+6-LTS"\nIMPLEMENTOR="Eclipse Adoptium"\n')
            index=root/'assets/indexes/16.json';index.parent.mkdir(parents=True);index.write_bytes(b'{}')
            base={'assetIndex':{'id':'16','sha1':hashlib.sha1(b'{}').hexdigest()},'arguments':{'jvm':['-cp','${classpath}'],'game':['--version','${version_name}','--accessToken','${auth_access_token}','--clientId','${clientid}','--xuid','${auth_xuid}']}}
            forge={'arguments':{'jvm':['-Djava.net.preferIPv6Addresses=system'],'game':['--launchTarget','forge_client']},'mainClass':'net.minecraftforge.bootstrap.ForgeBootstrap'}
            record={'library_directory':str(root/'sdk/libraries'),'classpath':[str(root/'1.21.1.jar')],'natives':str(root/'natives'),'system':'windows','architecture':'amd64'}
            with patch('native120_client.load',return_value=(base,forge,record)):
                result=native120_client.command(java,root/'home',root/'sdk',root/'assets',root/'prepared',username='Fixture')
                with patch.dict('os.environ',{'LOOTABLEBODIES_TEST_SESSION_ENDPOINT':'http://127.0.0.1:51927'}):
                    owned=native120_client.command(java,root/'home',root/'sdk',root/'assets',root/'prepared',username='Fixture')
                with patch.dict('os.environ',{'LOOTABLEBODIES_TEST_SESSION_ENDPOINT':'https://example.invalid'}), self.assertRaisesRegex(ValueError,'loopback'):
                    native120_client.command(java,root/'home',root/'sdk',root/'assets',root/'prepared',username='Fixture')
            self.assertIn('-Djava.net.preferIPv6Addresses=system',result)
            self.assertIn(str(root/'1.21.1.jar'),result)
            self.assertIn('net.minecraftforge.bootstrap.ForgeBootstrap',result)
            self.assertEqual('forge_client',result[result.index('--launchTarget')+1])
            self.assertFalse(any('ignoreList' in arg for arg in result))
            self.assertIn('-Dstdout.encoding=UTF-8',result)
            self.assertIn('-Dstderr.encoding=UTF-8',result)
            self.assertEqual('1.21.1-forge-52.1.20',result[result.index('--version')+1])
            self.assertEqual('0',result[result.index('--accessToken')+1])
            self.assertEqual('',result[result.index('--clientId')+1]);self.assertEqual('',result[result.index('--xuid')+1])
            self.assertIn('-Dminecraft.api.session.host=http://127.0.0.1:51927',owned)
            self.assertIn('-Dminecraft.api.services.host=http://127.0.0.1:51927',owned)

    def test_unsealed_classpath_and_changed_native_bytes_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);library=root/'native.dll';library.write_bytes(b'original')
            record={'version':native120_client.VERSION,'sdk':str(root.resolve()),'system':'windows','architecture':'amd64',
                    'files':{str(library):native_runtime.digest(library)},'classpath':[str(root/'unsealed.jar')]}
            (root/'inputs.json').write_text(json.dumps(record))
            with patch('native120_client.descriptors',return_value=({},{})),patch('native120_client.platform.system',return_value='Windows'),patch('native120_client.platform.architecture',return_value=('64bit','')):
                with self.assertRaisesRegex(RuntimeError,'Unsealed'):
                    native120_client.load(root,root)
                library.write_bytes(b'changed')
                with self.assertRaisesRegex(RuntimeError,'Changed prepared'):
                    native120_client.load(root,root)


if __name__=='__main__':unittest.main()
