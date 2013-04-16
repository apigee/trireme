var assert = require('assert');
var http = require('http');
var argo = require('../');

describe('IncomingMessage', function() {
  describe('#_addHeaderLine', function() {
    it('saves raw header names', function() {
      argo();

      var incomingMessage = new http.IncomingMessage();
      incomingMessage._addHeaderLine('Super-Duper', 'funtime');
      assert.equal(incomingMessage._rawHeaderNames['super-duper'], 'Super-Duper');
    });
  });
});
