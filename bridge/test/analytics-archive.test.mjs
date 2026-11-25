import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { AnalyticsArchive } from '../src/analytics-archive.js';
test('archive persists posts, replaces observations, and isolates changed account IDs', async t => {
 const dir=await fs.mkdtemp(path.join(os.tmpdir(),'analytics-archive-'));
 t.after(()=>fs.rm(dir,{recursive:true,force:true}));
 const repo={filePath:path.join(dir,'history.json')};
 const archive=new AnalyticsArchive(repo);
 await archive.record('example','1',[{id:'10',ts:10,likes:1}]);
 await archive.record('example','1',[{id:'20',ts:20,likes:2},{id:'10',ts:10,likes:3}]);
 const restarted=new AnalyticsArchive(repo);
 assert.deepEqual((await restarted.get('example')).posts.map(p=>p.likes),[3,2]);
 await restarted.record('example','2',[{id:'30',ts:30}]);
 assert.equal((await restarted.get('example')).posts.length,1);
 assert.equal((await restarted.get('missing')).posts.length,0);
});

test('archive counts original posts separately from authored replies', async t => {
 const dir=await fs.mkdtemp(path.join(os.tmpdir(),'analytics-counts-'));
 t.after(()=>fs.rm(dir,{recursive:true,force:true}));
 const archive=new AnalyticsArchive({filePath:path.join(dir,'history.json')});
 await archive.record('example','1',[{id:'1',ts:1,isReply:false},{id:'2',ts:2,isReply:true}]);
 const result=await archive.get('example');
 assert.equal(result.postCount,1);
 assert.equal(result.replyCount,1);
 const empty=await archive.get('missing');
 assert.equal(empty.postCount,0);
 assert.equal(empty.replyCount,0);
});
