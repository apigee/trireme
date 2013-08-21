var https = require('https');
var fs = require('fs');

var options = {
  key: fs.readFileSync('./test/fixtures/keys/agent1-key.pem'),
  cert: fs.readFileSync('./test/fixtures/keys/agent1-cert.pem')
};

https.createServer(options, function (request, response) {
  console.log('Got HTTP request');
  response.writeHead(200, {'Content-Type': 'text/plain'});
  response.end('Hello World\n');
}).listen(8124);

console.log('Server running at http://127.0.0.1:8124/');
