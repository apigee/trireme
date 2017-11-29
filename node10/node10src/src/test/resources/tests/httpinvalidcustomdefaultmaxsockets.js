var customMaxSockets = process.env.NODE_HTTP_MAX_SOCKETS = "invalid";

var http = require('http');
var assert = require('assert');

assert.equal(http.globalAgent.maxSockets, 5);
