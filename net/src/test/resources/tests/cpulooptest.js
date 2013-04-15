var http = require('http');
var assert = require('assert');

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

svr.listen(33333, function() {
  http.get('http://localhost:33333/', function(resp) {
    svr.close();
    assert.equal(500, resp.statusCode, 'Expected status code 500');

    resp.on('readable', function() {
      resp.read();
    });
  });
});
