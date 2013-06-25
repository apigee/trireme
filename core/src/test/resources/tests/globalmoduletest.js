var assert = require('assert');
var module = require('globaltestmodule');

assert.equal('globaltestmodule', module.modulename);
assert.equal('globaltestmodule', module.modulefunc());
