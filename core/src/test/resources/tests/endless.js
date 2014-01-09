console.log('Starting a script that will endlessly run tasks');

var count = 0;
var looping = function() {
  if ((count++ % 10000) == 0) {
    console.log('Looping(' + count + ')...');
  }
  setImmediate(looping);
}

looping();
