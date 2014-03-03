var child = require('child_process');

var echo = child.spawn('echo', ['Hello, World!']);
echo.on('close', function(code) {
  console.log('Echo completed with ' + code);
});
