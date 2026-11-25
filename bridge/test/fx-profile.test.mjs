import assert from 'node:assert/strict';
import test from 'node:test';
import { fxProfileUrl, fxUserId } from '../src/fx-profile.js';

test('known profile IDs take precedence and unknown handles use the current API', () => {
  assert.equal(fxUserId('bad', '', '123', '456'), '123');
  assert.equal(fxProfileUrl('old_name', '123').pathname, '/2/profile/id%3A123');
  assert.equal(fxProfileUrl('@example').pathname, '/2/profile/example');
  assert.equal(fxProfileUrl('example', '', 'http://127.0.0.1:1234/').origin, 'http://127.0.0.1:1234');
});
