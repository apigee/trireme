try {
  gc();
  console.log('gc=true');
} catch (e) {
  console.log('gc=false');
}

console.log('throwDeprecation=%s', (process.throwDeprecation === true))
console.log('traceDeprecation=%s', (process.traceDeprecation === true))