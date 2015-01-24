var testmodule = require('./testmodule');
var assert = require('assert');

function logerrno(e) {
  console.log('Errno = ' + e);
}

/*
errno = 'NOTSET';
logerrno(errno);
errno = 'SET';
logerrno(errno);
console.log('From module:');
assert('SET', testmodule.geterrno());
logerrno(testmodule.geterrno());
*/
console.log('Module set to MODULE');
testmodule.seterrno('MODULE');
assert('MODULE', testmodule.geterrno());
assert('MODULE', errno);
logerrno(errno);


