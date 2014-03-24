var assert = require('assert');
var name = 'testmodule';


exports.modulename = 'testmodule';
exports.modulefunc = function() {
  return 'testmodule.modulefunc';
}
exports.geterrno = function() {
  return errno;
}
exports.seterrno = function(e) {
  errno = e;
}
assert.equal(process.argv.length, 2);



