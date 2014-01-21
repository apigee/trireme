#!/usr/bin/env python

import re
import sys

# Results tend to look like:
#   test-name a bunch of junk: result
# Where result is a floating-point number

RESULT = re.compile('^(.*):([\s]*)([0-9\.]+)([\s]*)$')

Results = dict()
LowThreshold = 0
HighThreshold = 0

def printUsage():
	print 'Usage: compare <file1> <file2> [threshold]'
	
	
def process(f):
  result = []
  while True:
    l = f.readline()
    if (len(l) == 0):
      break;
    m = RESULT.match(l)
    if (m != None):
      name = m.group(1)
      val = float(m.group(3))
      result.append((name, val))
  return result
  
def load(res, index):
  for r in res:
    nums = Results.get(r[0])
    if (nums == None):
      nums = [0, 0]
      Results[r[0]] = nums
    nums[index] = r[1]

if (len(sys.argv) < 3):
	printUsage()
	sys.exit(2)
if (len(sys.argv) >= 4):
  thresh = int(sys.argv[3])
  LowThreshold = 100 - thresh
  HighThreshold = 100 + thresh

file1 = open(sys.argv[1], 'r')
results1 = process(file1)
load(results1, 0)

file2 = open(sys.argv[2], 'r')
results2 = process(file2)
load(results2, 1)

final = Results.items()
final.sort(key=lambda i: i[0])

for f in final:
  val1 = f[1][0]
  val2 = f[1][1]
  if ((val1 != 0) and (val2 != 0)):
    difference = (val2 / val1) * 100
    if ((difference < LowThreshold) or (difference > HighThreshold)):
      print '%s %f %f %f' % (f[0], val1, val2, difference)

