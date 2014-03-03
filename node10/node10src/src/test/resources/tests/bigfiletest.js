var assert = require('assert');
var big5 = require('./big5-func');

assert.deepEqual(big5.lookup('33185'), 21667);
assert.deepEqual(big5.lookup('NaN'), 0);
