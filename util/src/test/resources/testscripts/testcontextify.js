var assert = require('assert');

// Look for the file "contextify.node" to indicate that a binary file COULD exist.
// Then it will be intercepted and loaded as Java code.
var contextify = require('../contextify');

assert(contextify);

// Test the "dlopen" SPI directly. We use the real contextify module and test suite elsewhere
// for a more comprehensive test. This is just a sanity check of the Java code.
var sandbox = { console : console, prop1 : 'prop1'};
var ctx = new contextify.ContextifyContext(sandbox);
ctx.run('console.log(prop1);');

sandbox = {};
ctx = new contextify.ContextifyContext(sandbox);
ctx.run('var x = 3;');
assert.equal(sandbox.x, 3);

var script = new contextify.ContextifyScript('var x = 3;');
sandbox = {};
ctx = new contextify.ContextifyContext(sandbox);
script.runInContext(ctx);
assert.equal(sandbox.x, 3);

sandbox = {setTimeout : setTimeout};
ctx = new contextify.ContextifyContext(sandbox);
ctx.run("setTimeout(function () { x = 3; }, 100);");
assert.equal(sandbox.x, undefined);
setTimeout(function () {
  assert.equal(sandbox.x, 3);
}, 200);




