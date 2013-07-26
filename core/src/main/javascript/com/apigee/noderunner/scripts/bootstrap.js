/*
 * Copyright (C) 2013 Apigee Corp. and other Noderunner contributors.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
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

