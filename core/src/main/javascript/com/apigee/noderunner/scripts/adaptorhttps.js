/*
 * This is a wrapper around the https module from node that uses our adaptors when configured, and native
 * Node code when not.
 */

var NodeHttps = require('node_https');
var HttpWrap = process.binding('http_wrap');

var debug;
if (process.env.NODE_DEBUG && /https/.test(process.env.NODE_DEBUG)) {
  debug = function(x) { console.error('HTTP: %s', x); };
} else {
  debug = function() { };
}

if (HttpWrap.hasServerAdapter()) {
  debug('Using HTTP adapter for https');
  var http = require('http');
  var util = require('util');

  function Server(opts, requestListener) {
    if (!(this instanceof Server)) return new Server(opts, requestListener);
    http.Server.call(requestListener);
    this.tlsParams = opts;
  }
  util.inherits(Server, http.Server);

  exports.Server = Server;
  exports.createServer = function(opts, requestListener) {
    return new Server(opts, requestListener);
  };

} else {
  debug('Using Node HTTPS module');
  exports.Server = NodeHttps.Server;
  exports.createServer = NodeHttps.createServer;
}

exports.globalAgent = NodeHttps.globalAgent;
exports.Agent = NodeHttps.Agent;
exports.request = NodeHttps.request;
exports.get = NodeHttps.get;
