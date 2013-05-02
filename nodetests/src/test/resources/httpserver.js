var http = require('http');

http.createServer(function (request, response) {
  console.log('Got HTTP request');
  request.on('readable', function() {
    request.read();
  });
  request.on('end', function() {
    if (request.url === '/exit') {
      process.exit(4);
    } else {
      response.writeHead(200, {'Content-Type': 'text/plain'});
      response.end('Hello World\n');
    }
  });
}).listen(8124);

console.log('Server running at http://127.0.0.1:8124/');
