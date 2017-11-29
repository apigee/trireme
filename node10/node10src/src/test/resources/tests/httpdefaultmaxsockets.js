var http = require('http');
var assert = require('assert');

assert.equal(http.globalAgent.maxSockets, 5);
