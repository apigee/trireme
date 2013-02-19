// This test is supposed to ensure that we can't modify system-level objects

var assert = require('assert');

assert.throws(function() {
  String.prototype.fooBar = function() {
    return 'Foo the bar';
  }
  var s = new String();
  console.log(s.fooBar());
}, 'Modifying sealed object should have thrown an exception');
