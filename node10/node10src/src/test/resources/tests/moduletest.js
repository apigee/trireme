var name = 'moduletest';
var testmodule = require('./testmodule');
var testmodule2 = require('./testmodule2');
var assert = require('assert');

console.log('name = %s\n', name);
console.log('testmodule.modulename = %s\n', testmodule.modulename);
console.log('testmodule.modulefunc() = %s\n', testmodule.modulefunc());

assert.equal('moduletest', name);

console.log('testmodule2.modulename = %s\n', testmodule2.modulename);
console.log('testmodule2.modulefunc() = %s\n', testmodule2.modulefunc());

process.exit(0);
