var http = require('http');

var svr = http.createServer(function(req, resp) {
  console.log('Got an HTTP request');
  req.on('end', function() {
    resp.end('Hello, World!');
  });
});

console.log('Server starting to listen');
svr.listen(33333, function() {
  console.log('Server listening');
  http.get('http://localhost:33333/', function(resp) {
    console.log('Got a response with status code ' + resp.statusCode);
    if (resp.statusCode != 200) {
      process.exit(1);
    }
    svr.close();
  });
});
