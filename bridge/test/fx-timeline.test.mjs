import test from 'node:test';
import assert from 'node:assert/strict';
import { fxTimelineUrl } from '../src/fx-timeline.js';
import { ownFxPostEligible } from '../src/post-ranking.js';
test('timeline request omits the failing since filter and retains bounded count', () => {
 const url=fxTimelineUrl('example',100);
 assert.equal(url.pathname,'/2/profile/example/statuses');
 assert.equal(url.searchParams.get('count'),'100');
 assert.equal(url.searchParams.has('since'),false);
 assert.equal(url.searchParams.get('with_replies'),'true');
 assert.equal(fxTimelineUrl('example',200,'next').searchParams.get('cursor'),'next');
});
test('bridge still excludes older posts after unfiltered retrieval', () => {
 const now=Date.now(); const post={type:'status',id:'1',author:{screen_name:'example'},url:'https://x.com/example/status/1',created_timestamp:Math.floor((now-8*86400000)/1000)};
 assert.equal(ownFxPostEligible(post,'example',{windowStart:now-7*86400000}),false);
 post.created_timestamp=Math.floor((now-86400000)/1000);
 assert.equal(ownFxPostEligible(post,'example',{windowStart:now-7*86400000}),true);
});
