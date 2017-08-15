// Ported version of https://github.com/nodejs/node/commit/d6969a717f

var common = require('../common');
var assert = require('assert');
var http = require('http');
var url = require('url');

var opts = url.parse('http://127.0.0.1:8180');

opts.auth = 100;

assert.throws(function () {
  http.get(opts);
}, /^TypeError: "value" argument must not be a number$/);
