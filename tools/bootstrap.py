#!/usr/bin/env python3
"""Fetch pinned public build dependencies and verify SHA-256 before use."""
import hashlib,os,pathlib,urllib.request,zipfile,json
ROOT=pathlib.Path(__file__).resolve().parents[1]
CACHE=pathlib.Path(os.environ.get('TOOLS_DIR',ROOT/'.tools'));CACHE.mkdir(parents=True,exist_ok=True)
lock=json.loads((ROOT/'tools/dependencies.json').read_text())
for item in lock:
 dest=CACHE/item['file'];dest.parent.mkdir(parents=True,exist_ok=True)
 if not dest.exists() or hashlib.sha256(dest.read_bytes()).hexdigest()!=item['sha256']:
  part=dest.with_suffix(dest.suffix+'.part')
  with urllib.request.urlopen(item['url'],timeout=180) as response,part.open('wb') as out:
   import shutil;shutil.copyfileobj(response,out)
  if hashlib.sha256(part.read_bytes()).hexdigest()!=item['sha256']:
   part.unlink();raise SystemExit('Checksum mismatch: '+item['file'])
  part.replace(dest)
 if item.get('extract'):
  target=CACHE/item['extract'];target.mkdir(parents=True,exist_ok=True)
  with zipfile.ZipFile(dest) as archive:
   for member in archive.infolist():
    p=(target/member.filename).resolve()
    if not p.is_relative_to(target.resolve()):raise SystemExit('Unsafe archive member')
   archive.extractall(target)
 print(item['file'],'verified')
