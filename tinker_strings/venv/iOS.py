#
# Created by Raimundas Sakalauskas on 2019-08-29.
# Copyright (c) 2019 Particle. All rights reserved.
#

import sys
import json
import io
from datetime import datetime

jsonPath = 'TinkerStrings.json'
stringsPath = 'TinkerStrings.strings'
swiftPath = 'TinkerStrings.swift'
methodName = 'tinkerLocalized()'
useObjC = False

if (len(sys.argv) > 1):
    jsonPath = sys.argv[1]
    stringsPath = sys.argv[2]
    swiftPath = sys.argv[3]
    methodName = sys.argv[4]

if (len(sys.argv) > 5):
    useObjC = sys.argv[5] == 'objc'

def outputStrings(json, key, output):
    for n in sorted(json):
        if isinstance(json[n], dict):
            key.append(n)
            outputStrings(json[n], key, output)
            key.pop()
        else:
            output.write(u'\"%s.%s\" = \"%s\";\n' % ('.'.join(key), n, json[n]))
    output.write(u'\n')



def outputSwift(json, key, output):
    for n in sorted(json):
        if isinstance(json[n], dict):
            output.write(u'%senum %s {\n' % ('\t' * len(key), n))
            key.append(n)
            outputSwift(json[n], key, output)
            key.pop()
            output.write(u'%s}\n' % ('\t' * len(key)))
        else:
            output.write(u'%sstatic let %s = "%s.%s".%s\n' % ('\t' * len(key), n, '.'.join(key), n, methodName))

def outputObjC(json, key, output):
    for n in sorted(json):
        if isinstance(json[n], dict):
            key.append(n)
            outputObjC(json[n], key, output)
            key.pop()
        else:
            output.write(u'#define %s_%s NSLocalizedStringFromTableInBundle(@\"%s.%s\", @\"ParticleSetupStrings\", [ParticleSetupMainController getResourcesBundle], @\"\")\n' % ('_'.join(key), n, '.'.join(key), n))
    output.write(u'\n')

#open data as
data = json.load(io.open(jsonPath, 'r', encoding='utf8'))
key = []

#prepare for the output
output = io.open(stringsPath, 'w', encoding='utf8')
output.write(u'// Generated on %s by iOS.py\n\n' % datetime.now().strftime("%Y-%m-%d %H:%M:%S"))
outputStrings(data, key, output)
output.close()

#prepare for the output
output = io.open(swiftPath, 'w', encoding='utf8')
output.write(u'// Generated on %s by iOS.py\n\n' % datetime.now().strftime("%Y-%m-%d %H:%M:%S"))
if (useObjC == False):
    outputSwift(data, key, output)
else:
    outputObjC(data, key, output)
output.close()


