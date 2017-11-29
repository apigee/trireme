var customMaxSockets = process.env.NODE_HTTP_MAX_SOCKETS = 10;

var http = require('http');
var assert = require('assert');

assert.equal(http.globalAgent.maxSockets, customMaxSockets);
