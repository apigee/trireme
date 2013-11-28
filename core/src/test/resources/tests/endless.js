console.log('Starting a script that will endlessly run tasks');

var count = 0;
var looping = function() {
  //if ((count++ % 10) == 0) {
    console.log('Looping(' + count + ')...');
  //}
  setImmediate(looping);
};

console.log('Going to call nextTick');
process.nextTick(looping);

console.log('Put something in the next tick queue');