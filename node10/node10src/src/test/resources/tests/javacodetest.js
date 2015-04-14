// We should not be allowed to call Java code from inside Node scripts using Rhino's stuff.

var assert = require('assert');

assert.throws(function() {
  var date = new java.util.Date();
  console.log('Java says that the date is ' + date.toString());
}, 'Should have received an exception here');

assert.throws(function() {
  var dateClass = getClass('java.util.Date');
}, 'Should have received exception on getClass');
