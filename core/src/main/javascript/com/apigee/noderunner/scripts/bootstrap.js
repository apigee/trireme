var Module = require('module');
var path = require('path');

exports.evalScript = function(name, s) {
  var cwd = process.cwd();

  var module = new Module(name);
  module.filename = path.join(cwd, name);
  module.paths = Module._nodeModulePaths(cwd);
  var script = s;
  if (!Module._contextLoad) {
    var body = script;
    script = 'global.__filename = ' + JSON.stringify(name) + ';\n' +
      'global.exports = exports;\n' +
      'global.module = module;\n' +
      'global.__dirname = __dirname;\n' +
      'global.require = require;\n' +
      'return require("vm").runInThisContext(' +
      JSON.stringify(body) + ', ' +
      JSON.stringify(name) + ', true);\n';
  }
  var result = module._compile(script, name + '-wrapper');
  if (process._print_eval) console.log(result);
};

