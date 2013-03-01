/*
 * Patch any parts of the net module that break on Noderunner.
 */

var NodeNet = require('node_net');
var cares = process.binding('cares_wrap');

for (var k in NodeNet) {
  exports[k] = NodeNet[k];
}

exports.isIP = function(hostname) {
   return cares.isIP(hostname);
}
