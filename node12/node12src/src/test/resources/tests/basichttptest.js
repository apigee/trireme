var http = require('http');

var svr = http.createServer(function(req, resp) {
  console.log('Got an HTTP request');
  req.on('data', function(chunk) {
    console.log('Server got data: ' + chunk);
  });
  req.on('end', function() {
    console.log('Server got end');
    resp.end('Hello, World!');
  });
});

console.log('Server starting to listen');
svr.listen(43333, function() {
  console.log('Server listening');
  http.get('http://localhost:43333/', function(resp) {
    console.log('Got a response with status code ' + resp.statusCode);
    if (resp.statusCode != 200) {
      process.exit(1);
    }
    resp.on('data', function(chunk) {
      console.log(chunk);
    });
    resp.on('end', function() {
      console.log('Got the whole response');
      svr.close();
    });
  });
});
