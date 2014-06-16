// This test assumes that it is run with the arguments, "One," "Two", "Three"

var assert = require('assert');

assert.equal(process.argv.length, 5);
assert.equal(process.argv[2], 'One');
assert.equal(process.argv[3], 'Two');
assert.equal(process.argv[4], 'Three');

process.argv[4] = 'New';
assert.equal(process.argv[4], 'New');
process.argv[0] = 'NotNode';
assert.equal(process.argv[0], 'NotNode');

process.argv = [];
process.argv.push('NotEvenNode');
process.argv.push('JS');

assert.equal(process.argv.length, 2);
assert.equal(process.argv[0], 'NotEvenNode');
assert.equal(process.argv[1], 'JS');
