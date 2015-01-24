var assert = require('assert');
var path = require('path');

var trireme = process.binding('trireme-native-support');

var stringArg = (process.argv.length > 2 ? process.argv[2] : 'Foo');
var intArg = (process.argv.length > 3 ? process.argv[3] : '123');

// Load test jars
trireme.loadJars([
  path.join(__dirname, '../testjar.jar'),
  path.join(__dirname, '../depjar.jar')
]);

// Now require should work
var testMod = require('test-jar-module');

assert.equal(testMod.getValue(), null);
testMod.setValue(stringArg);
assert.equal(testMod.getValue(), stringArg);

assert.equal(testMod.getSharedValue(), 0);
testMod.setSharedValue(parseInt(intArg));
assert.equal(testMod.getSharedValue(), parseInt(intArg));

// Load not found
assert.throws(function() {
  trireme.loadJars(['jarnotfound.jar']);
});

// Not an array of strings
assert.throws(function() {
  trireme.loadJars(123);
});


