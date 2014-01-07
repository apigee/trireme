var express = require('express');
var app = express();

var port;
if (process.argv.length > 2) {
  port = process.argv[2];
} else {
  port = 33333;
}

app.use(express.bodyParser());

app.get('/dogs', function(req, res) {
  res.setHeader('Content-Type', 'text/plain');
  res.end('I like dogs');
});

app.post('/dogs', function(req, res) {
  res.setHeader('Content-Type', 'application/json');
  res.send(req.body);
});

app.post('/dogs2', function(req, res) {
  req.on('data', function(chunk) {
    console.log('dogs2: Got ' + chunk);
  });
  req.on('end', function() {
    console.log('dogs2: Got end');
    res.end('ok');
  });
});

app.listen(port);

