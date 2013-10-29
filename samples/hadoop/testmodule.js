// map: ctx has "collect" and "done" methods
//       input is the first value, which is always a string

function map(ctx, input) {
  var words = input.split(' ');
  for (i in words) {
    var val = { count: 1 };
    // "collect" takes two strings as arguments
    ctx.collect(words[i], JSON.stringify(val));
  }
  // You must call this or the job will hang
  ctx.done();
}
module.exports.map = map;

// reduce: ctx also has "nextValue" which returns the values from "map"
//         key is of course the key from map as well
function reduce(ctx, key) {
  var finalCount = 0;
  var value;
  do {
    // Get the next value, or null if there are no more
    value = ctx.nextValue();
    if (value) {
      var valueObj = JSON.parse(value);
      finalCount += valueObj.count;
    }
  } while (value);
  
  var finalValue = { count: finalCount };
  var strValue = JSON.stringify(finalValue);
  // Collect is the same as before
  ctx.collect(key, strValue);
  // Like before you must call this or we hang
  ctx.done();
}
module.exports.reduce = reduce;
