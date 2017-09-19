var assert = require('assert');
var http = require('http');
var util = require('util');

var svr = http.createServer(function(req, resp) {
  console.log('Starting to loop the CPU');
  var c = 0;
  while (true) {
    c++;
  }
  req.on('readable', function() {
    var chunk = req.read();
  });
});

svr.listen(0, function() {
  var url = util.format('http://localhost:%d', svr.address().port);
  http.get(url, function(resp) {
    svr.close();
    assert.equal(500, resp.statusCode, 'Expected status code 500');

    resp.on('readable', function() {
      resp.read();
    });
  });
});
