var fs = require('fs');
var argo = require('../');

argo()
  .get('/hello.txt', function(addHandler) {
    addHandler('request', function(env, next) {
      var filename = __dirname + '/hello.txt';
      fs.stat(filename, function(err, stats) {
        console.log(stats);
        env.response.headers['Content-Length'] = stats.size;
        env.response.headers['Content-Type'] = 'text/plain';
        env.response.body = fs.createReadStream(filename)
        next(env);
      });
    });
  })
  .listen(process.env.PORT || 3000);
