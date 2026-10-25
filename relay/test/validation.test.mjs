import test from 'node:test'; import assert from 'node:assert/strict';
test('remote identifiers reject injection and short values',()=>{const re=/^[A-Za-z0-9._:-]{8,160}$/;assert.equal(re.test('abcd'),false);assert.equal(re.test('../bad-session'),false);assert.equal(re.test('session_12345678'),true)});
