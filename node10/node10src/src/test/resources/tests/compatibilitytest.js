var assert = require('assert');

// The way many Node.js modules escape URIs.
// This will break if "escape" looks at the second parameter.
var output = encodeURIComponent('Hello, World!');
output = output.replace(/[^A-Za-z0-9_.~\-%]+/g, escape);

var hello = 'Hello, World!';
assert.equal((' \t \n \r' + hello + ' \t \n \r').trim(), hello);

assert.equal(hello.trimLeft(), hello);
assert.equal((' ' + hello).trimLeft(), hello);
assert.equal((' \t \n \r' + hello).trimLeft(), hello);
assert.equal((' \t \n \r' + hello + ' \t \n \r').trimLeft(), (hello + ' \t \n \r'));

assert.equal(hello.trimRight(), hello);
assert.equal((hello + ' ').trimRight(), hello);
assert.equal((hello + ' \t \n \r').trimRight(), hello);
assert.equal((' \t \n \r' + hello + ' \t \n \r').trimRight(), (' \t \n \r' + hello));
