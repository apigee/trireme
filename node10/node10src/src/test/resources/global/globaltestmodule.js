var name = 'globaltestmodule';

var testmodule2 = module.exports;

testmodule2.modulename = name;
testmodule2.modulefunc = function() {
  return name;
}
