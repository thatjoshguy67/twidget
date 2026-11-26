import test from 'node:test';
import assert from 'node:assert/strict';
import { assessFollowerEvents, followerEventService } from '../src/follower-events.js';
const DAY=86400000, now=Date.UTC(2026,10,26,12), today=Math.floor(now/DAY)*DAY;
function cohort({accounts=60, affected=40, badSource=false}={}) {
 return Array.from({length:accounts},(_,i)=>Array.from({length:17},(_,j)=>({accountId:String(i),ts:today-(16-j)*DAY,
 followers:(i%2?20000:2000)+j*2-(j===16&&i<affected?(i%2?200:20):0),known:true,source:badSource?'client':'live'}))).flat();
}
test('detects a broad correlated anomaly without claiming a purge or exposing accounts',()=>{
 const r=assessFollowerEvents(cohort(),now);assert.equal(r.status,'detected');assert.equal(r.event.affectedAccounts,40);
 assert.equal(r.event.cause,'unknown');assert.equal(r.event.confidence,'tentative');assert.equal(JSON.stringify(r).includes('accountId'),false);
});
test('ordinary loss, inadequate samples and non-live rows do not produce a shared event',()=>{
 assert.equal(assessFollowerEvents(cohort({affected:1}),now).event,null);
 assert.equal(assessFollowerEvents(cohort({accounts:10,affected:10}),now).status,'insufficient_data');
 assert.equal(assessFollowerEvents(cohort({badSource:true}),now).event,null);
});
test('estimates, unknowns, duplicate account observations and future rows cannot inflate cohort',()=>{
 const rows=cohort();const bad=rows.map(r=>({...r,accountId:'one'}));
 assert.equal(assessFollowerEvents(bad,now).event,null);
 assert.equal(assessFollowerEvents(rows.map(r=>({...r,estimated:true})),now).event,null);
 assert.equal(assessFollowerEvents(rows.map(r=>({...r,known:false})),now).event,null);
});
test('cache coalesces cohort reads and storage failures fail open with no assertion',async()=>{
 let calls=0;const get=followerEventService({followerEventObservations:async()=>{calls++;return cohort()}},{now:()=>now});
 await Promise.all([get(),get()]);await get();assert.equal(calls,1);
 const failed=followerEventService({followerEventObservations:async()=>{throw Error('db')}},{now:()=>now});
 assert.equal((await failed()).status,'unavailable');assert.equal((await failed()).event,null);
});
