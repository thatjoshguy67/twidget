import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { AnalyticsArchive } from '../src/analytics-archive.js';
import { backfillAnalytics, fetchTimelinePage } from '../src/analytics-backfill.js';
const status=(id,reply=false,author='example')=>({type:'status',id,author:{screen_name:author},url:`https://x.com/${author}/status/${id}`,created_timestamp:1577836800,replying_to:reply?'someone':null});
async function setup(t) {
 const dir=await fs.mkdtemp(path.join(os.tmpdir(),'backfill-'));
 t.after(()=>fs.rm(dir,{recursive:true,force:true}));
 return new AnalyticsArchive({filePath:path.join(dir,'history.json')});
}
const normalize=s=>({id:s.id,ts:s.created_timestamp*1000,isReply:!!s.replying_to});
test('backfill retains old originals and replies, excludes other authors, resumes and deduplicates',async t=>{
 const archive=await setup(t); const options={archive,username:'example',profile:{userId:'1'},normalize,maxPages:1};
 let result=await backfillAnalytics({...options,page:async()=>({results:[status('1'),status('2',true),status('3',false,'other')],cursor:'next'})});
 assert.equal(result.postCount,1);assert.equal(result.replyCount,1);assert.equal(result.backfill.status,'partial');
 assert.equal(result.backfill.oldestPostAt,1577836800000);
 result=await backfillAnalytics({...options,page:async(u,c)=>{assert.equal(c,'next');return {results:[status('1'),status('4')],cursor:null}}});
 assert.equal(result.posts.length,3);assert.equal(result.coverage.sourceExhausted,true);assert.equal(result.complete,false);
 assert.equal(result.coverage.earliestPostAt,1577836800000);
});
test('backfill progress ignores unrelated older archive rows and stays unknown on an empty page',async t=>{
 const archive=await setup(t);
 await archive.record('example','1',[{id:'99',ts:1,isReply:false}]);
 const o={archive,username:'example',profile:{userId:'1'},normalize,maxPages:1};
 let r=await backfillAnalytics({...o,page:async()=>({results:[],cursor:'a'})});
 assert.equal(r.backfill.oldestPostAt,null);
 r=await backfillAnalytics({...o,page:async()=>({results:[status('1')],cursor:'b'})});
 assert.equal(r.backfill.oldestPostAt,1577836800000);
 assert.equal(r.coverage.earliestPostAt,1);
});
test('errors preserve resume cursor; repeated cursor stalls instead of claiming complete',async t=>{
 const archive=await setup(t);const o={archive,username:'example',profile:{userId:'1'},normalize,maxPages:1};
 await backfillAnalytics({...o,page:async()=>({results:[status('1')],cursor:'a'})});
 let r=await backfillAnalytics({...o,page:async()=>{throw Error('upstream unavailable')}});
 assert.equal(r.backfill.status,'error');assert.equal(r.backfill.cursor,'a');assert.equal(r.posts.length,1);
 r=await backfillAnalytics({...o,page:async()=>({results:[],cursor:'a'})});
 assert.equal(r.backfill.status,'stalled');assert.equal(r.coverage.sourceExhausted,false);
});
test('timeline adapter sends replies and cursor; 404 never means exhaustion',async()=>{
 await assert.rejects(fetchTimelinePage('example','a',async url=>{
 assert.equal(url.searchParams.get('cursor'),'a');assert.equal(url.searchParams.get('with_replies'),'true');
 return {ok:false,status:404,json:async()=>({results:[]})};
 }),/404/);
});
test('concurrent local backfills cannot race the checkpoint',async t=>{
 const archive=await setup(t);let release;const barrier=new Promise(r=>release=r);
 const first=archive.withBackfillLock('example',()=>barrier);
 assert.equal(await archive.withBackfillLock('example',()=>assert.fail()),null);
 release('ok');assert.equal(await first,'ok');
});
test('reply endpoint fallback is explicit and standard cursor mode remains stable',async()=>{
 const urls=[];
 const result=await fetchTimelinePage('example',null,async url=>{
  urls.push(url);
  if(url.searchParams.has('with_replies'))return {ok:false,status:404,json:async()=>({})};
  return {ok:true,status:200,json:async()=>({results:[],cursor:{bottom:'a'}})};
 });
 assert.equal(urls.length,2);assert.equal(result.mode,'standard');assert.equal(result.cursor,'a');
});
