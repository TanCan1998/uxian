#!/usr/bin/env python3
"""Publish the tested CI artifact and mirror identical bytes to a module repo."""
import os,pathlib,subprocess,json
root=pathlib.Path(__file__).resolve().parents[1]
config=json.loads((root/'release.json').read_text())
repo=os.environ['GITHUB_REPOSITORY'];tag=os.environ['GITHUB_REF_NAME']
if not os.environ.get('MODULE_REPO_TOKEN'):raise SystemExit('MODULE_REPO_TOKEN is required before a public release')
# The generated APK must use the durable signing identity, never an ephemeral PR key.
# RELEASE_KEYSTORE is checked via the repository secret configuration before tagging.
def gh(args,token=None):
 env=dict(os.environ)
 if token:env['GH_TOKEN']=token
 subprocess.run(['gh']+args,env=env,check=True)
assets=[str(p) for p in (root/'dist').iterdir() if p.is_file()]
notes=root/'RELEASE_NOTES.md'
gh(['release','create',tag,'--repo',repo,'--verify-tag','--title',config['title']+' '+tag,'--notes-file',str(notes)]+assets)
module=config['module_repository'];module_tag=str(config['version_code'])+'-'+config['version_name']
gh(['release','create',module_tag,'--repo',module,'--title',config['version_name'],'--notes-file',str(notes)]+assets,os.environ['MODULE_REPO_TOKEN'])
print('Source and module releases published from identical CI artifacts')
