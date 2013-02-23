/*
 * This is a wrapper around the https module from node that uses our adaptors when configured, and native
 * Node code when not.
 */

var NodeHttps = require('node_https');

exports.Server = NodeHttps.Server;
exports.createServer = NodeHttps.createServer;
exports.globalAgent = NodeHttps.globalAgent;
exports.Agent = NodeHttps.Agent;
exports.request = NodeHttps.request;
exports.get = NodeHttps.get;
