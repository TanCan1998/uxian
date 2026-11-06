#!/usr/bin/env python3
"""Sign using a persistent private key in CI, or the tool's test key locally."""
import os,pathlib,subprocess,sys
args=['java','-jar',str(pathlib.Path(os.environ.get('DL','.tools'))/'signer.jar'),'--allowResign','--skipZipAlign','-a',sys.argv[1],'-o',sys.argv[2]]
key=os.environ.get('SIGNING_KEYSTORE')
if os.environ.get('CI') and not key:raise SystemExit('CI requires SIGNING_KEYSTORE')
if key:args+=['--ks',key,'--ksAlias','release','--ksPass',os.environ['SIGNING_PASSWORD'],'--ksKeyPass',os.environ['SIGNING_PASSWORD']]
p=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
if p.returncode:raise SystemExit('APK signing failed; diagnostic output suppressed to protect signing secrets')
print('APK signing succeeded')
